package dev.ufz.mcloud.mixin;

import dev.ufz.mcloud.network.MCloudServerMultiplexer;
import io.netty.channel.Channel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Injects MCloudServerMultiplexer at the very beginning of the server pipeline.
 */
@Mixin(targets = "net.minecraft.server.network.ServerConnectionListener$1")
public class ServerConnectionListenerInitMixin {
    @Inject(method = "initChannel(Lio/netty/channel/Channel;)V", at = @At("RETURN"))
    private void mcloud$addServerMultiplexer(Channel channel, CallbackInfo ci) {
        // Add multiplexer at the very front of the pipeline (before 'timeout' and 'legacy_query')
        if (channel.pipeline().get("mcloud_multiplexer") == null) {
            channel.pipeline().addFirst("mcloud_multiplexer", new MCloudServerMultiplexer());
        }
    }
}
