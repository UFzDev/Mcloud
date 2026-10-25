package dev.ufz.mcloud.mixin;

import dev.ufz.mcloud.MCloudMod;
import dev.ufz.mcloud.tunnel.CloudflaredManager;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
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
        if (mc.player != null) {
            mc.player.sendSystemMessage(
                    Component.literal("[MCloud] ")
                            .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD)
                            .append(Component.literal("Creating Cloudflare Tunnel for friends...").withStyle(ChatFormatting.GRAY))
            );
        }

        CloudflaredManager.start(port, tunnelDomain -> {
            // Ensure UI/Chat message is scheduled on Minecraft's main client thread
            mc.execute(() -> {
                if (mc.player != null) {
                    Component linkComponent = Component.literal(tunnelDomain)
                            .withStyle(style -> style
                                    .withColor(ChatFormatting.GREEN)
                                    .withUnderlined(true)
                                    .withBold(true)
                                    .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, tunnelDomain))
                                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Click to copy tunnel address")))
                            );

                    mc.player.sendSystemMessage(
                            Component.literal("\n========================================\n")
                                    .withStyle(ChatFormatting.DARK_AQUA)
                                    .append(Component.literal("  [MCloud] PUBLIC WORLD ONLINE!\n").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
                                    .append(Component.literal("  Share this address with your friends:\n").withStyle(ChatFormatting.YELLOW))
                                    .append(Component.literal("  >>> ").withStyle(ChatFormatting.AQUA))
                                    .append(linkComponent)
                                    .append(Component.literal(" <<<\n").withStyle(ChatFormatting.AQUA))
                                    .append(Component.literal("  (Click link to copy directly to clipboard)\n").withStyle(ChatFormatting.DARK_GRAY))
                                    .append(Component.literal("========================================\n").withStyle(ChatFormatting.DARK_AQUA))
                    );
                }
            });
        });
    }

    @Inject(method = "halt(Z)V", at = @At("HEAD"))
    private void mcloud(boolean waitForShutdown, CallbackInfo ci) {
        if (CloudflaredManager.isRunning()) {
            MCloudMod.LOGGER.info("[MCloud] Singleplayer world closing. Shutting down active tunnel...");
            CloudflaredManager.stop();
        }
    }
}