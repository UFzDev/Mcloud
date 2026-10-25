package dev.ufz.mcloud.mixin;

import dev.ufz.mcloud.network.MCloudClientConnectHandler;
import io.netty.channel.Channel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.network.Connection$1")
public class ConnectionClientInitMixin {

    @Inject(method = "initChannel(Lio/netty/channel/Channel;)V", at = @At("RETURN"))
    private void mcloud(Channel channel, CallbackInfo ci) {
        // Add outbound connect interceptor at the front of the pipeline.
        // When Bootstrap.connect(...) executes, MCloudClientConnectHandler will inspect the target
        // address and inject SslHandler and MCloudClientHandshakeHandler before TCP connection completes.
        channel.pipeline().addFirst("mcloud_connect_interceptor", new MCloudClientConnectHandler());
    }
}
