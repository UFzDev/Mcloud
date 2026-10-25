package dev.ufz.mcloud.mixin;

import dev.ufz.mcloud.MCloudMod;
import dev.ufz.mcloud.tunnel.CloudflaredManager;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.level.GameType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Automatically spawns a Cloudflare tunnel and posts a click-to-copy URL in the player's chat
 * when the game is opened to LAN.
 */
@Mixin(IntegratedServer.class)
public class IntegratedServerLanMixin {

    @Inject(method = "publishServer(Lnet/minecraft/world/level/GameType;ZI)Z", at = @At("RETURN"))
    private void mcloud(GameType gameMode, boolean cheats, int port, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValue()) {
            return;
        }

        MCloudMod.LOGGER.info("[MCloud] IntegratedServer opened to LAN on local port {}. Starting Cloudflare tunnel...", port);

        Minecraft mc = Minecraft.getInstance();
        MutableComponent startingMsg = Component.empty()
                .append(Component.literal("MCloud ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
                .append(Component.literal("» ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal("Creating secure Cloudflare tunnel...").withStyle(ChatFormatting.GRAY));

        sendChatMessage(mc, startingMsg);

        CloudflaredManager.start(port, tunnelDomain -> {
            // Ensure UI/Chat message is scheduled on Minecraft's main client thread
            mc.execute(() -> {
                MutableComponent copyButton = Component.literal("[COPY LINK]")
                        .withStyle(style -> style
                                .withColor(ChatFormatting.GREEN)
                                .withBold(true)
                                .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, tunnelDomain))
                                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                        Component.literal("Click to copy address to clipboard").withStyle(ChatFormatting.GREEN)))
                        );

                MutableComponent domainText = Component.literal(tunnelDomain)
                        .withStyle(style -> style
                                .withColor(ChatFormatting.AQUA)
                                .withUnderlined(true)
                                .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, tunnelDomain))
                                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                        Component.literal("Click to copy address to clipboard").withStyle(ChatFormatting.GREEN)))
                        );

                MutableComponent message = Component.empty()
                        .append(Component.literal("----------------------------------------\n").withStyle(ChatFormatting.DARK_GRAY))
                        .append(Component.literal("MCloud ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
                        .append(Component.literal("» ").withStyle(ChatFormatting.DARK_GRAY))
                        .append(Component.literal("Public world is now ONLINE!\n").withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD))
                        .append(Component.literal("Address: ").withStyle(ChatFormatting.GRAY))
                        .append(domainText)
                        .append(Component.literal("  "))
                        .append(copyButton)
                        .append(Component.literal("\n----------------------------------------").withStyle(ChatFormatting.DARK_GRAY));

                sendChatMessage(mc, message);
            });
        });
    }

    private static void sendChatMessage(Minecraft mc, Component component) {
        if (mc.gui != null && mc.gui.getChat() != null) {
            mc.gui.getChat().addMessage(component);
        } else if (mc.player != null) {
            mc.player.sendSystemMessage(component);
        }
    }

    @Inject(method = "halt(Z)V", at = @At("HEAD"))
    private void mcloud(boolean waitForShutdown, CallbackInfo ci) {
        if (CloudflaredManager.isRunning()) {
            MCloudMod.LOGGER.info("[MCloud] Singleplayer world closing. Shutting down active tunnel...");
            CloudflaredManager.stop();
        }
    }
}
