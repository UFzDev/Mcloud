package dev.ufz.mcloud;

import com.mojang.brigadier.CommandDispatcher;
import dev.ufz.mcloud.config.MCloudConfig;
import dev.ufz.mcloud.network.MCloudAddressRegistry;
import dev.ufz.mcloud.tunnel.CloudflaredManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
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

        // Server lifecycle hooks
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            if (config.enabled && config.autoStartCloudflared) {
                int port = server.getPort();
                LOGGER.info("[MCloud] Server started on port {}. Launching Cloudflare Tunnel...", port);
                CloudflaredManager.start(port);
            }
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (CloudflaredManager.isRunning()) {
                LOGGER.info("[MCloud] Server stopping. Terminating Cloudflare Tunnel...");
                CloudflaredManager.stop();
            }
        });
    }

    private void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("mcloud")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("status")
                        .executes(ctx -> {
                            CommandSourceStack src = ctx.getSource();
                            if (CloudflaredManager.isRunning()) {
                                String url = CloudflaredManager.getActiveUrl();
                                if (url != null) {
                                    String cleanAddress = url.replace("https://", "");
                                    Component link = Component.literal("§a" + cleanAddress)
                                            .withStyle(style -> style
                                                    .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, cleanAddress))
                                                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Click to copy address"))));

                                    src.sendSuccess(() -> Component.literal("§b[MCloud] §fTunnel is §aONLINE§f!\n§bServer Address: ").append(link), false);
                                } else {
                                    src.sendSuccess(() -> Component.literal("§b[MCloud] §eTunnel process is starting, waiting for URL..."), false);
                                }
                            } else {
                                src.sendSuccess(() -> Component.literal("§b[MCloud] §cTunnel is currently stopped. Use §f/mcloud start §cto launch."), false);
                            }
                            return 1;
                        }))
                .then(Commands.literal("start")
                        .executes(ctx -> {
                            CommandSourceStack src = ctx.getSource();
                            if (CloudflaredManager.isRunning()) {
                                src.sendFailure(Component.literal("§c[MCloud] Tunnel is already running!"));
                                return 0;
                            }
                            int port = src.getServer().getPort();
                            CloudflaredManager.start(port);
                            src.sendSuccess(() -> Component.literal("§a[MCloud] Starting Cloudflare tunnel for port " + port + "..."), true);
                            return 1;
                        }))
                .then(Commands.literal("stop")
                        .executes(ctx -> {
                            CommandSourceStack src = ctx.getSource();
                            if (!CloudflaredManager.isRunning()) {
                                src.sendFailure(Component.literal("§c[MCloud] Tunnel is not running."));
                                return 0;
                            }
                            CloudflaredManager.stop();
                            src.sendSuccess(() -> Component.literal("§e[MCloud] Cloudflare tunnel stopped."), true);
                            return 1;
                        }))
                .then(Commands.literal("restart")
                        .executes(ctx -> {
                            CommandSourceStack src = ctx.getSource();
                            int port = src.getServer().getPort();
                            CloudflaredManager.stop();
                            CloudflaredManager.start(port);
                            src.sendSuccess(() -> Component.literal("§a[MCloud] Restarting Cloudflare tunnel on port " + port + "..."), true);
                            return 1;
                        }))
        );
    }
}
