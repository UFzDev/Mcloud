package dev.ufz.mcloud;

import com.mojang.brigadier.CommandDispatcher;
import dev.ufz.mcloud.config.MCloudConfig;
import dev.ufz.mcloud.network.MCloudAddressRegistry;
import dev.ufz.mcloud.tunnel.CloudflaredManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MCloudMod implements ModInitializer {
    public static final String MOD_ID = "mcloud";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("[MCloud] Initializing Cloudflare WebSocket & Zero-Config Tunnel Engine...");

        MCloudConfig config = MCloudConfig.get();
        for (String customDomain : config.customCloudflareDomains) {
            MCloudAddressRegistry.registerCustomDomain(customDomain);
        }

        // Register /mcloud commands
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> registerCommands(dispatcher));

        // Server lifecycle hooks - ONLY auto-start tunnel on DEDICATED servers, not singleplayer worlds!
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            if (server.isDedicatedServer() && config.enabled && config.autoStartCloudflared) {
                int port = server.getPort();
                LOGGER.info("[MCloud] Dedicated server started on port {}. Launching Cloudflare Tunnel...", port);
                CloudflaredManager.start(port);
            }
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (server.isDedicatedServer() && CloudflaredManager.isRunning()) {
                LOGGER.info("[MCloud] Dedicated server stopping. Terminating Cloudflare Tunnel...");
                CloudflaredManager.stop();
            }
        });
    }

    public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("mcloud")
                .then(Commands.literal("status")
                        .executes(ctx -> {
                            CommandSourceStack src = ctx.getSource();
                            if (CloudflaredManager.isRunning()) {
                                String url = CloudflaredManager.getActiveUrl();
                                if (url != null) {
                                    String cleanAddress = url.replace("https://", "");
                                    MutableComponent link = Component.literal(cleanAddress)
                                            .withStyle(ChatFormatting.AQUA, ChatFormatting.UNDERLINE);

                                    MutableComponent response = Component.empty()
                                            .append(Component.literal("MCloud ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
                                            .append(Component.literal("» ").withStyle(ChatFormatting.DARK_GRAY))
                                            .append(Component.literal("Tunnel is ").withStyle(ChatFormatting.WHITE))
                                            .append(Component.literal("ONLINE\n").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD))
                                            .append(Component.literal("Address: ").withStyle(ChatFormatting.GRAY))
                                            .append(link);

                                    src.sendSuccess(() -> response, false);
                                } else {
                                    MutableComponent waiting = Component.empty()
                                            .append(Component.literal("MCloud ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
                                            .append(Component.literal("» ").withStyle(ChatFormatting.DARK_GRAY))
                                            .append(Component.literal("Tunnel process starting, waiting for public URL...").withStyle(ChatFormatting.YELLOW));
                                    src.sendSuccess(() -> waiting, false);
                                }
                            } else {
                                MutableComponent stopped = Component.empty()
                                        .append(Component.literal("MCloud ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
                                        .append(Component.literal("» ").withStyle(ChatFormatting.DARK_GRAY))
                                        .append(Component.literal("Tunnel is offline. Use ").withStyle(ChatFormatting.RED))
                                        .append(Component.literal("/mcloud start").withStyle(ChatFormatting.WHITE))
                                        .append(Component.literal(" to launch.").withStyle(ChatFormatting.RED));
                                src.sendSuccess(() -> stopped, false);
                            }
                            return 1;
                        }))
                                .then(Commands.literal("start")
                        .executes(ctx -> {
                            CommandSourceStack src = ctx.getSource();
                            net.minecraft.server.MinecraftServer server = src.getServer();

                            if (CloudflaredManager.isRunning()) {
                                String url = CloudflaredManager.getActiveUrl();
                                String msg = url != null ? "MCloud » Tunnel is already running at: " + url.replace("https://", "") : "MCloud » Tunnel is already running!";
                                src.sendFailure(Component.literal(msg).withStyle(ChatFormatting.YELLOW));
                                return 0;
                            }

                            // If singleplayer / integrated server and NOT yet published, publish it automatically!
                            if (!server.isDedicatedServer() && !server.isPublished()) {
                                int availablePort = net.minecraft.util.HttpUtil.getAvailablePort();
                                boolean published = false;

                                // Try modern 26.x publishServer(MultiplayerScope, boolean, int)
                                try {
                                    for (java.lang.reflect.Method m : server.getClass().getMethods()) {
                                        if (m.getName().equals("publishServer") && m.getParameterCount() == 3) {
                                            Class<?> p0 = m.getParameterTypes()[0];
                                            if (p0.isEnum()) {
                                                for (Object constant : p0.getEnumConstants()) {
                                                    if (constant.toString().equals("LAN")) {
                                                        published = (boolean) m.invoke(server, constant, false, availablePort);
                                                        break;
                                                    }
                                                }
                                            }
                                            if (published) break;
                                        }
                                    }
                                } catch (Throwable ignored) {
                                }

                                // Try pre-26.x publishServer(GameType, boolean, int)
                                if (!published) {
                                    try {
                                        for (java.lang.reflect.Method m : server.getClass().getMethods()) {
                                            if (m.getName().equals("publishServer") && m.getParameterCount() == 3) {
                                                Class<?> p0 = m.getParameterTypes()[0];
                                                if (p0.getName().endsWith("GameType")) {
                                                    published = (boolean) m.invoke(server, null, false, availablePort);
                                                    break;
                                                }
                                            }
                                        }
                                    } catch (Throwable ignored) {
                                    }
                                }

                                if (published) {
                                    src.sendSuccess(() -> Component.literal("MCloud » World opened to LAN on port " + availablePort + "! Starting Cloudflare tunnel...").withStyle(ChatFormatting.GREEN), true);
                                    // LanServerPingerMixin will automatically handle starting the tunnel and notifying chat
                                    return 1;
                                } else {
                                    src.sendFailure(Component.literal("MCloud » Failed to open LAN world. Please open via World Options.").withStyle(ChatFormatting.RED));
                                    return 0;
                                }
                            }

                            int port = server.getPort();
                            CloudflaredManager.start(port);
                            src.sendSuccess(() -> Component.literal("MCloud » Starting Cloudflare tunnel on port " + port + "...").withStyle(ChatFormatting.GREEN), true);
                            return 1;
                        }))
                .then(Commands.literal("stop")
                        .executes(ctx -> {
                            CommandSourceStack src = ctx.getSource();
                            if (!CloudflaredManager.isRunning()) {
                                src.sendFailure(Component.literal("MCloud » Tunnel is not running.").withStyle(ChatFormatting.RED));
                                return 0;
                            }
                            CloudflaredManager.stop();
                            src.sendSuccess(() -> Component.literal("MCloud » Cloudflare tunnel stopped.").withStyle(ChatFormatting.YELLOW), true);
                            return 1;
                        }))
                .then(Commands.literal("restart")
                        .executes(ctx -> {
                            CommandSourceStack src = ctx.getSource();
                            int port = src.getServer().getPort();
                            CloudflaredManager.stop();
                            CloudflaredManager.start(port);
                            src.sendSuccess(() -> Component.literal("MCloud » Restarting Cloudflare tunnel on port " + port + "...").withStyle(ChatFormatting.GREEN), true);
                            return 1;
                        }))
        );
    }
}
