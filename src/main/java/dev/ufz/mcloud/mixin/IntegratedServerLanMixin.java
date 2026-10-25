package dev.ufz.mcloud.mixin;

import dev.ufz.mcloud.MCloudMod;
import dev.ufz.mcloud.tunnel.CloudflaredManager;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.level.GameType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Automatically spawns a Cloudflare tunnel and posts the public URL in the player's chat
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
                MutableComponent message = null;

                // Try to parse full clickable and copy-to-clipboard JSON text component via registry access
                if (mc.level != null) {
                    try {
                        String rawJson = String.format(
                                "{\"text\":\"\",\"extra\":["
                                        + "{\"text\":\"----------------------------------------\\n\",\"color\":\"dark_gray\"},"
                                        + "{\"text\":\"MCloud \",\"color\":\"gold\",\"bold\":true},"
                                        + "{\"text\":\"» \",\"color\":\"dark_gray\"},"
                                        + "{\"text\":\"Public world is now ONLINE!\\n\",\"color\":\"white\",\"bold\":true},"
                                        + "{\"text\":\"Address: \",\"color\":\"gray\"},"
                                        + "{\"text\":\"%s\",\"color\":\"aqua\",\"underlined\":true,\"clickEvent\":{\"action\":\"copy_to_clipboard\",\"value\":\"%s\"},\"hoverEvent\":{\"action\":\"show_text\",\"value\":\"Click to copy address\"}},"
                                        + "{\"text\":\"  \"},"
                                        + "{\"text\":\"[COPY LINK]\",\"color\":\"green\",\"bold\":true,\"clickEvent\":{\"action\":\"copy_to_clipboard\",\"value\":\"%s\"},\"hoverEvent\":{\"action\":\"show_text\",\"value\":\"Click to copy address\"}},"
                                        + "{\"text\":\"\\n----------------------------------------\",\"color\":\"dark_gray\"}"
                                        + "]}",
                                tunnelDomain, tunnelDomain, tunnelDomain
                        );

                        message = Component.Serializer.fromJson(rawJson, mc.level.registryAccess());
                    } catch (Throwable t) {
                        MCloudMod.LOGGER.warn("[MCloud] Registry-aware JSON parsing failed, using clean fallback", t);
                    }
                }

                if (message == null) {
                    message = Component.empty()
                            .append(Component.literal("----------------------------------------\n").withStyle(ChatFormatting.DARK_GRAY))
                            .append(Component.literal("MCloud ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
                            .append(Component.literal("» ").withStyle(ChatFormatting.DARK_GRAY))
                            .append(Component.literal("Public world is now ONLINE!\n").withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD))
                            .append(Component.literal("Address: ").withStyle(ChatFormatting.GRAY))
                            .append(Component.literal(tunnelDomain).withStyle(ChatFormatting.AQUA, ChatFormatting.UNDERLINE))
                            .append(Component.literal("\n----------------------------------------").withStyle(ChatFormatting.DARK_GRAY));
                }

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
