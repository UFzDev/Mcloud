package dev.ufz.mcloud.mixin;

import dev.ufz.mcloud.MCloudMod;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Connection.class)
public abstract class ConnectionChannelActiveMixin {

    @Shadow
    protected abstract void flushQueue();

    @Inject(method = "channelActive(Lio/netty/channel/ChannelHandlerContext;)V", at = @At("TAIL"))
    private void mcloudOnChannelActive(ChannelHandlerContext ctx, CallbackInfo ci) {
        MCloudMod.LOGGER.info("[MCloud] Connection.channelActive triggered, flushing queued packets...");
        this.flushQueue();
    }
}
