package dev.ufz.mcloud.network;

import io.netty.util.AttributeKey;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks and normalizes server addresses that should be connected via MCloud WebSocket tunnels.
 */
public final class MCloudAddressRegistry {
    public static final AttributeKey<EndpointInfo> ENDPOINT_ATTR = AttributeKey.valueOf("mcloud_endpoint_info");

    public record EndpointInfo(String host, int port, boolean useTls, String path) {}

    private static final Map<String, EndpointInfo> EXPLICIT_ENDPOINTS = new ConcurrentHashMap<>();
    private static final Map<String, EndpointInfo> IP_TO_ENDPOINT = new ConcurrentHashMap<>();
    private static final Set<String> CUSTOM_DOMAINS = ConcurrentHashMap.newKeySet();
    private static final Map<String, Boolean> DNS_CACHE = new ConcurrentHashMap<>();

    private MCloudAddressRegistry() {}

    public static void registerCustomDomain(String domain) {
        if (domain != null && !domain.isBlank()) {
            CUSTOM_DOMAINS.add(domain.trim().toLowerCase(Locale.ROOT));
        }
    }

    public static void clear() {
        EXPLICIT_ENDPOINTS.clear();
        IP_TO_ENDPOINT.clear();
        CUSTOM_DOMAINS.clear();
        DNS_CACHE.clear();
    }

    public static void mapIpToEndpoint(String ip, EndpointInfo endpoint) {
        if (ip != null && endpoint != null) {
            IP_TO_ENDPOINT.put(ip, endpoint);
        }
    }

    /**
     * Fast, strictly non-blocking string parsing for the Minecraft Multiplayer menu UI/Render thread.
     * Evaluates only local patterns and memory caches. NEVER performs network/DNS lookups here.
     */
    public static String normalizeServerAddressInput(String rawInput) {
        if (rawInput == null) {
            return null;
        }
        String trimmed = rawInput.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);

        Boolean explicitTls = null;
        String remainder = trimmed;

        if (lower.startsWith("wss://")) {
            explicitTls = true;
            remainder = trimmed.substring(6);
        } else if (lower.startsWith("https://")) {
            explicitTls = true;
            remainder = trimmed.substring(8);
        } else if (lower.startsWith("mcloud://")) {
            explicitTls = true;
            remainder = trimmed.substring(9);
        } else if (lower.startsWith("ws://")) {
            explicitTls = false;
            remainder = trimmed.substring(5);
        } else if (lower.startsWith("http://")) {
            explicitTls = false;
            remainder = trimmed.substring(7);
        }

        // Extract optional path
        String path = "/mcloud";
        int slashIdx = remainder.indexOf('/');
        if (slashIdx >= 0) {
            String rawPath = remainder.substring(slashIdx).trim();
            if (!rawPath.isEmpty() && !rawPath.equals("/")) {
                path = rawPath;
            }
            remainder = remainder.substring(0, slashIdx);
        }

        String host = remainder;
        int explicitPort = -1;
        int colonIdx = remainder.lastIndexOf(':');
        if (colonIdx > 0 && remainder.indexOf(':') == colonIdx) {
            String portPart = remainder.substring(colonIdx + 1);
            try {
                explicitPort = Integer.parseInt(portPart);
                host = remainder.substring(0, colonIdx);
            } catch (NumberFormatException ignored) {
            }
        }

        String hostLower = host.toLowerCase(Locale.ROOT);
        boolean isKnownTunnel = isKnownTunnelDomain(hostLower);

        if (explicitTls != null || isKnownTunnel) {
            boolean useTls = explicitTls != null ? explicitTls : true;
            int targetPort = explicitPort > 0 ? explicitPort : (useTls ? 443 : 80);
            EXPLICIT_ENDPOINTS.put(hostLower, new EndpointInfo(host, targetPort, useTls, path));
            return explicitPort > 0 ? (host + ":" + explicitPort) : host;
        }

        return remainder;
    }

    /**
     * Fast in-memory check without performing blocking DNS network calls.
     */
    public static boolean isKnownTunnelDomain(String hostLower) {
        if (hostLower == null || hostLower.isBlank()) {
            return false;
        }
        if (hostLower.endsWith(".trycloudflare.com") || hostLower.endsWith(".cfargotunnel.com") || hostLower.endsWith(".workers.dev")) {
            return true;
        }
        for (String custom : CUSTOM_DOMAINS) {
            if (hostLower.equals(custom) || hostLower.endsWith("." + custom)) {
                return true;
            }
        }
        // Check if previously resolved by DNS TXT in memory
        return Boolean.TRUE.equals(DNS_CACHE.get(hostLower));
    }

    /**
     * Checks whether a custom domain has a Cloudflare / MCloud TXT record (_mcloud.domain).
     * ONLY called from asynchronous background threads (e.g. Netty IO / Server Pinger / Resolver).
     */
    /**
     * Backward-compatible helper method used by background resolver threads.
     */
    public static boolean isCloudflareTunnelDomain(String host) {
        if (host == null || host.isBlank()) return false;
        String h = host.toLowerCase(Locale.ROOT);
        return isKnownTunnelDomain(h) || checkDnsTxtRecordAsync(h);
    }
    public static boolean checkDnsTxtRecordAsync(String domain) {
        if (domain == null || domain.isBlank()) return false;
        String hostLower = domain.toLowerCase(Locale.ROOT);
        Boolean cached = DNS_CACHE.get(hostLower);
        if (cached != null) {
            return cached;
        }

        try {
            java.util.Hashtable<String, String> env = new java.util.Hashtable<>();
            env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
            env.put("com.sun.jndi.dns.timeout.initial", "1000"); // 1s timeout
            env.put("com.sun.jndi.dns.timeout.retries", "1");
            javax.naming.directory.DirContext ctx = new javax.naming.directory.InitialDirContext(env);
            javax.naming.directory.Attributes attrs = ctx.getAttributes("_mcloud." + hostLower, new String[]{"TXT"});
            if (attrs != null && attrs.get("TXT") != null) {
                DNS_CACHE.put(hostLower, true);
                return true;
            }
        } catch (Exception ignored) {
        }

        DNS_CACHE.put(hostLower, false);
        return false;
    }

    /**
     * Resolves whether an outbound Minecraft connection should use MCloud WebSocket tunneling.
     * Invoked from Netty / Pinger threads during connect/resolve.
     */
    public static EndpointInfo resolveEndpoint(InetSocketAddress address) {
        if (address == null) {
            return null;
        }
        String host = address.getHostString();
        if (host != null) {
            String hostLower = host.toLowerCase(Locale.ROOT);
            EndpointInfo registered = EXPLICIT_ENDPOINTS.get(hostLower);
            if (registered != null) {
                int port = (address.getPort() != 25565 && address.getPort() > 0)
                        ? address.getPort()
                        : registered.port();
                return new EndpointInfo(host, port, registered.useTls(), registered.path());
            }

            if (isKnownTunnelDomain(hostLower) || checkDnsTxtRecordAsync(hostLower)) {
                int port = (address.getPort() == 25565 || address.getPort() <= 0) ? 443 : address.getPort();
                EndpointInfo endpoint = new EndpointInfo(host, port, true, "/mcloud");
                EXPLICIT_ENDPOINTS.put(hostLower, endpoint);
                return endpoint;
            }
        }

        InetAddress inetAddress = address.getAddress();
        if (inetAddress != null) {
            String ip = inetAddress.getHostAddress();
            EndpointInfo mapped = IP_TO_ENDPOINT.get(ip);
            if (mapped != null) {
                return mapped;
            }
        }

        return null;
    }

    public static void registerEndpoint(String host, int port, boolean useTls, String path) {
        EXPLICIT_ENDPOINTS.put(host.toLowerCase(Locale.ROOT), new EndpointInfo(host, port, useTls, path));
    }
}