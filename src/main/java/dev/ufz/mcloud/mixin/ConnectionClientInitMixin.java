package dev.ufz.mcloud.mixin;

import dev.ufz.mcloud.network.MCloudAddressRegistry;
import dev.ufz.mcloud.network.MCloudClientHandshakeHandler;
import io.netty.channel.Channel;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.net.ssl.SSLException;
import java.net.InetSocketAddress;

@Mixin(targets = "net.minecraft.network.Connection$1")
public class ConnectionClientInitMixin {
    private static SslContext sslContext;

    private static synchronized SslContext getSslContext() {
        if (sslContext == null) {
            try {
                sslContext = SslContextBuilder.forClient().build();
            } catch (SSLException e) {
                try {
                    sslContext = SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build();
                } catch (SSLException ignored) {
                }
            }
        }
        return sslContext;
    }

    @Inject(method = "initChannel(Lio/netty/channel/Channel;)V", at = @At("RETURN"))
    private void mcloud$addClientWebSocketHandler(Channel channel, CallbackInfo ci) {
        if (channel.remoteAddress() instanceof InetSocketAddress remote) {
            MCloudAddressRegistry.EndpointInfo endpoint = MCloudAddressRegistry.resolveEndpoint(remote);
            if (endpoint != null) {
                if (endpoint.useTls()) {
                    SslContext ssl = getSslContext();
                    if (ssl != null) {
                        channel.pipeline().addFirst("mcloud_ssl", ssl.newHandler(channel.alloc(), endpoint.host(), endpoint.port()));
                    }
                }
                channel.pipeline().addAfter(
                        channel.pipeline().get("mcloud_ssl") != null ? "mcloud_ssl" : null,
                        "mcloud_client_handshake",
                        new MCloudClientHandshakeHandler(endpoint.host(), endpoint.path())
                );
            }
        }
    }
}
