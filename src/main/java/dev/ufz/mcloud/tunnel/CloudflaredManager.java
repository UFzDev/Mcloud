package dev.ufz.mcloud.tunnel;

import dev.ufz.mcloud.MCloudMod;
import dev.ufz.mcloud.config.MCloudConfig;
import net.fabricmc.loader.api.FabricLoader;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Autonomous Cloudflared tunnel process lifecycle manager.
 * Spawns 'cloudflared tunnel --url http://127.0.0.1:<port>', extracts the free https://*.trycloudflare.com URL,
 * and maintains the background process.
 */
public class CloudflaredManager {
    private static final Pattern URL_PATTERN = Pattern.compile("https://[a-zA-Z0-9-]+\\.trycloudflare\\.com");
    private static Process process;
    private static final AtomicReference<String> activeUrl = new AtomicReference<>(null);
    private static final AtomicBoolean running = new AtomicBoolean(false);

    public interface TunnelListener {
        void onTunnelReady(String tunnelUrl);
    }

    public static void start(int localPort) {
        start(localPort, null);
    }

    public static void start(int localPort, TunnelListener listener) {
        if (running.get()) {
            MCloudMod.LOGGER.info("[MCloud] Cloudflared is already running at {}", activeUrl.get());
            return;
        }

        // Run binary discovery, download and process launching completely asynchronously
        // to NEVER freeze Minecraft's Render / GUI thread
        Thread launchThread = new Thread(() -> {
            synchronized (CloudflaredManager.class) {
                if (running.get()) return;

                MCloudConfig config = MCloudConfig.get();
                Path binary = findOrDownloadCloudflared(config);
                if (binary == null) {
                    MCloudMod.LOGGER.error("[MCloud] Could not find or download cloudflared executable! Please install cloudflared manually.");
                    return;
                }

                try {
                    ProcessBuilder pb;
                    if ("token".equalsIgnoreCase(config.tunnelMode) && config.tunnelToken != null && !config.tunnelToken.isBlank()) {
                        MCloudMod.LOGGER.info("[MCloud] Launching cloudflared with custom domain Zero Trust token...");
                        pb = new ProcessBuilder(binary.toString(), "tunnel", "run", "--token", config.tunnelToken);
                    } else {
                        MCloudMod.LOGGER.info("[MCloud] Launching cloudflared in Quick Tunnel mode for port {}...", localPort);
                        pb = new ProcessBuilder(binary.toString(), "tunnel", "--url", "http://127.0.0.1:" + localPort);
                    }

                    pb.redirectErrorStream(true);
                    process = pb.start();
                    running.set(true);

                    Thread logThread = new Thread(() -> {
                        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                Matcher matcher = URL_PATTERN.matcher(line);
                                if (matcher.find()) {
                                    String foundUrl = matcher.group();
                                    activeUrl.set(foundUrl);
                                    MCloudMod.LOGGER.info("==========================================================");
                                    MCloudMod.LOGGER.info("[MCloud] FREE CLOUDFLARE MINECRAFT TUNNEL ONLINE!");
                                    MCloudMod.LOGGER.info("[MCloud] Share this server address with your friends:");
                                    MCloudMod.LOGGER.info("[MCloud] >>> {} <<<", foundUrl.replace("https://", ""));
                                    MCloudMod.LOGGER.info("==========================================================");
                                    if (listener != null) {
                                        try {
                                            listener.onTunnelReady(foundUrl.replace("https://", ""));
                                        } catch (Exception e) {
                                            MCloudMod.LOGGER.error("[MCloud] Error notifying tunnel listener", e);
                                        }
                                    }
                                }
                            }
                        } catch (IOException ignored) {
                        } finally {
                            running.set(false);
                            activeUrl.set(null);
                        }
                    }, "MCloud-Cloudflared-Monitor");
                    logThread.setDaemon(true);
                    logThread.start();

                } catch (Exception e) {
                    MCloudMod.LOGGER.error("[MCloud] Failed to start cloudflared process", e);
                    running.set(false);
                }
            }
        }, "MCloud-Cloudflared-Launcher");
        launchThread.setDaemon(true);
        launchThread.start();
    }

    public static synchronized void stop() {
        if (process != null && process.isAlive()) {
            MCloudMod.LOGGER.info("[MCloud] Stopping cloudflared process...");
            process.destroy();
            try {
                process.waitFor();
            } catch (InterruptedException ignored) {
            }
            process = null;
        }
        running.set(false);
        activeUrl.set(null);
    }

    public static boolean isRunning() {
        return running.get() && process != null && process.isAlive();
    }

    public static String getActiveUrl() {
        return activeUrl.get();
    }

    public static Path findOrDownloadCloudflared(MCloudConfig config) {
        // 1. Check system PATH
        Path fromPath = findInSystemPath();
        if (fromPath != null) {
            return fromPath;
        }

        // 2. Check local mod config folder
        Path localBinDir = FabricLoader.getInstance().getConfigDir().resolve("mcloud").resolve("bin");
        String exeName = isWindows() ? "cloudflared.exe" : "cloudflared";
        Path localBinary = localBinDir.resolve(exeName);
        if (Files.isExecutable(localBinary)) {
            return localBinary;
        }

        // 3. Download if enabled
        if (config.autoDownloadCloudflared) {
            return downloadCloudflared(localBinary);
        }

        return null;
    }

    private static Path findInSystemPath() {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) return null;
        String exeName = isWindows() ? "cloudflared.exe" : "cloudflared";
        for (String dir : pathEnv.split(Pattern.quote(java.io.File.pathSeparator))) {
            Path candidate = Paths.get(dir).resolve(exeName);
            if (Files.isExecutable(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static Path downloadCloudflared(Path target) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);

        String assetName;
        if (os.contains("win")) {
            assetName = "cloudflared-windows-amd64.exe";
        } else if (os.contains("mac")) {
            assetName = arch.contains("aarch64") || arch.contains("arm") ? "cloudflared-darwin-arm64.tgz" : "cloudflared-darwin-amd64.tgz";
        } else {
            // Linux
            assetName = arch.contains("aarch64") || arch.contains("arm") ? "cloudflared-linux-arm64" : "cloudflared-linux-amd64";
        }

        String downloadUrl = "https://github.com/cloudflare/cloudflared/releases/latest/download/" + assetName;
        MCloudMod.LOGGER.info("[MCloud] Downloading official cloudflared binary from {}...", downloadUrl);

        try {
            Files.createDirectories(target.getParent());
            HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build();
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(downloadUrl)).header("User-Agent", "MCloud/1.0").build();
            HttpResponse<InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());

            if (resp.statusCode() == 200) {
                Files.copy(resp.body(), target, StandardCopyOption.REPLACE_EXISTING);
                if (!isWindows()) {
                    try {
                        Files.setPosixFilePermissions(target, Set.of(
                                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
                                PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE,
                                PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE
                        ));
                    } catch (Exception ignored) {
                    }
                }
                MCloudMod.LOGGER.info("[MCloud] Downloaded cloudflared successfully to {}", target);
                return target;
            } else {
                MCloudMod.LOGGER.error("[MCloud] Failed to download cloudflared: HTTP {}", resp.statusCode());
            }
        } catch (Exception e) {
            MCloudMod.LOGGER.error("[MCloud] Exception downloading cloudflared", e);
        }
        return null;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
