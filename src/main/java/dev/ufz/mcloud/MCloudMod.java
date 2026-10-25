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

    private void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
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
                            if (CloudflaredManager.isRunning()) {
                                src.sendFailure(Component.literal("MCloud » Tunnel is already running!").withStyle(ChatFormatting.RED));
                                return 0;
                            }
                            int port = src.getServer().getPort();
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
