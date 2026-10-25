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

import java.lang.reflect.Constructor;

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
                ClickEvent copyClickEvent = createCopyClickEvent(tunnelDomain);
                HoverEvent copyHoverEvent = createShowTextHoverEvent("Click to copy address to clipboard");

                MutableComponent copyButton = Component.literal("[COPY LINK]")
                        .withStyle(style -> {
                            style = style.withColor(ChatFormatting.GREEN).withBold(true);
                            if (copyClickEvent != null) style = style.withClickEvent(copyClickEvent);
                            if (copyHoverEvent != null) style = style.withHoverEvent(copyHoverEvent);
                            return style;
                        });

                MutableComponent domainText = Component.literal(tunnelDomain)
                        .withStyle(style -> {
                            style = style.withColor(ChatFormatting.AQUA).withUnderlined(true);
                            if (copyClickEvent != null) style = style.withClickEvent(copyClickEvent);
                            if (copyHoverEvent != null) style = style.withHoverEvent(copyHoverEvent);
                            return style;
                        });

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

    private static ClickEvent createCopyClickEvent(String text) {
        // 1. Try modern 1.21.2+ CopyToClipboard record
        try {
            Class<?> copyClass = null;
            for (String className : new String[]{
                    "net.minecraft.network.chat.ClickEvent$CopyToClipboard",
                    "net.minecraft.text.ClickEvent$CopyToClipboard",
                    "net.minecraft.class_2558$class_10606"
            }) {
                try {
                    copyClass = Class.forName(className);
                    break;
                } catch (ClassNotFoundException ignored) {
                }
            }

            if (copyClass != null) {
                Constructor<?> ctor = copyClass.getDeclaredConstructor(String.class);
                ctor.setAccessible(true);
                return (ClickEvent) ctor.newInstance(text);
            }
        } catch (Throwable ignored) {
        }

        // 2. Fallback to pre-1.21.2 ClickEvent(Action.COPY_TO_CLIPBOARD, String)
        try {
            for (Constructor<?> ctor : ClickEvent.class.getDeclaredConstructors()) {
                if (ctor.getParameterCount() == 2) {
                    Class<?> p1 = ctor.getParameterTypes()[0];
                    Class<?> p2 = ctor.getParameterTypes()[1];
                    if (p1.isEnum() && p2 == String.class) {
                        for (Object constant : p1.getEnumConstants()) {
                            if (constant.toString().equals("COPY_TO_CLIPBOARD")) {
                                ctor.setAccessible(true);
                                return (ClickEvent) ctor.newInstance(constant, text);
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        return null;
    }

    private static HoverEvent createShowTextHoverEvent(String text) {
        // 1. Try modern 1.21.2+ ShowText record
        try {
            Class<?> showTextClass = null;
            for (String className : new String[]{
                    "net.minecraft.network.chat.HoverEvent$ShowText",
                    "net.minecraft.text.HoverEvent$ShowText",
                    "net.minecraft.class_2568$class_10607"
            }) {
                try {
                    showTextClass = Class.forName(className);
                    break;
                } catch (ClassNotFoundException ignored) {
                }
            }

            if (showTextClass != null) {
                for (Constructor<?> ctor : showTextClass.getDeclaredConstructors()) {
                    if (ctor.getParameterCount() == 1 && Component.class.isAssignableFrom(ctor.getParameterTypes()[0])) {
                        ctor.setAccessible(true);
                        return (HoverEvent) ctor.newInstance(Component.literal(text));
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        // 2. Fallback to pre-1.21.2 HoverEvent(Action.SHOW_TEXT, Component)
        try {
            for (Constructor<?> ctor : HoverEvent.class.getDeclaredConstructors()) {
                if (ctor.getParameterCount() == 2) {
                    Class<?> p1 = ctor.getParameterTypes()[0];
                    Class<?> p2 = ctor.getParameterTypes()[1];
                    if (p1.isEnum() && Component.class.isAssignableFrom(p2)) {
                        for (Object constant : p1.getEnumConstants()) {
                            if (constant.toString().equals("SHOW_TEXT")) {
                                ctor.setAccessible(true);
                                return (HoverEvent) ctor.newInstance(constant, Component.literal(text));
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        return null;
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
