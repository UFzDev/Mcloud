package dev.ufz.mcloud.network;

import java.net.InetSocketAddress;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks and normalizes server addresses that should be connected via MCloud WebSocket tunnels.
 */
public final class MCloudAddressRegistry {
    public record EndpointInfo(String host, int port, boolean useTls, String path) {}

    private static final Map<String, EndpointInfo> EXPLICIT_ENDPOINTS = new ConcurrentHashMap<>();
    private static final Set<String> CUSTOM_DOMAINS = ConcurrentHashMap.newKeySet();

    private MCloudAddressRegistry() {}

    public static void registerCustomDomain(String domain) {
        if (domain != null && !domain.isBlank()) {
            CUSTOM_DOMAINS.add(domain.trim().toLowerCase(Locale.ROOT));
        }
    }

    public static void clear() {
        EXPLICIT_ENDPOINTS.clear();
        CUSTOM_DOMAINS.clear();
    }

    /**
     * Normalizes user-entered server addresses from the Minecraft Multiplayer menu.
     * Supports:
     * - https://words.trycloudflare.com
     * - wss://mc.example.com
     * - ws://127.0.0.1:25565
     * - mcloud://mc.example.com
     * - words.trycloudflare.com
     *
     * @param rawInput Raw string typed/pasted into Server Address box
     * @return Clean host[:port] string suitable for vanilla ServerAddress.parseString()
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
        // Ensure it's not an IPv6 literal bracket
        if (colonIdx > 0 && remainder.indexOf(':') == colonIdx) {
            String portPart = remainder.substring(colonIdx + 1);
            try {
                explicitPort = Integer.parseInt(portPart);
                host = remainder.substring(0, colonIdx);
            } catch (NumberFormatException ignored) {
            }
        }

        String hostLower = host.toLowerCase(Locale.ROOT);
        boolean isCloudflareAuto = isCloudflareTunnelDomain(hostLower);

        if (explicitTls != null || isCloudflareAuto) {
            boolean useTls = explicitTls != null ? explicitTls : true;
            int targetPort = explicitPort > 0 ? explicitPort : (useTls ? 443 : 80);
            EXPLICIT_ENDPOINTS.put(hostLower, new EndpointInfo(host, targetPort, useTls, path));
            return explicitPort > 0 ? (host + ":" + explicitPort) : host;
        }

        return remainder;
    }

    public static boolean isCloudflareTunnelDomain(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        String h = host.toLowerCase(Locale.ROOT);
        if (h.endsWith(".trycloudflare.com") || h.endsWith(".cfargotunnel.com") || h.endsWith(".workers.dev")) {
            return true;
        }
        for (String custom : CUSTOM_DOMAINS) {
            if (h.equals(custom) || h.endsWith("." + custom)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Resolves whether an outbound Minecraft connection should use MCloud WebSocket tunneling.
     */
    public static EndpointInfo resolveEndpoint(InetSocketAddress address) {
        if (address == null) {
            return null;
        }
        String host = address.getHostString();
        if (host == null) {
            return null;
        }
        String hostLower = host.toLowerCase(Locale.ROOT);

        EndpointInfo registered = EXPLICIT_ENDPOINTS.get(hostLower);
        if (registered != null) {
            int port = (address.getPort() != 25565 && address.getPort() > 0)
                    ? address.getPort()
                    : registered.port();
            return new EndpointInfo(host, port, registered.useTls(), registered.path());
        }

        if (isCloudflareTunnelDomain(hostLower)) {
            int port = (address.getPort() == 25565 || address.getPort() <= 0) ? 443 : address.getPort();
            return new EndpointInfo(host, port, true, "/mcloud");
        }

        return null;
    }

    /**
     * Registers an explicit endpoint directly (useful for testing and programmatic tunnels).
     */
    public static void registerEndpoint(String host, int port, boolean useTls, String path) {
        EXPLICIT_ENDPOINTS.put(host.toLowerCase(Locale.ROOT), new EndpointInfo(host, port, useTls, path));
    }
}
