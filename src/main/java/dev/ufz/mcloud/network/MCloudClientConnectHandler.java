package dev.ufz.mcloud.network;

import dev.ufz.mcloud.MCloudMod;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;

import javax.net.ssl.SSLException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

/**
 * An outbound handler placed in the Netty pipeline that intercepts the connect(...) call.
 * At connect time, the remote SocketAddress is guaranteed to be available, allowing MCloud
 * to dynamically inject TLS (SslHandler) and the WebSocket Handshake handler before any packets flow.
 */
public class MCloudClientConnectHandler extends ChannelOutboundHandlerAdapter {
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

    @Override
    public void connect(ChannelHandlerContext ctx, SocketAddress remoteAddress, SocketAddress localAddress, ChannelPromise promise) throws Exception {
        MCloudAddressRegistry.EndpointInfo endpoint = ctx.channel().attr(MCloudAddressRegistry.ENDPOINT_ATTR).get();

        if (endpoint == null && remoteAddress instanceof InetSocketAddress inet) {
            endpoint = MCloudAddressRegistry.resolveEndpoint(inet);
        }

        if (endpoint != null) {
            MCloudMod.LOGGER.info("[MCloud] Configuring SSL and WebSocket pipeline for target: {}:{} ({})",
                    endpoint.host(), endpoint.port(), endpoint.path());

            if (endpoint.useTls()) {
                SslContext ssl = getSslContext();
                if (ssl != null) {
                    // SslHandler must be the very first handler in the channel pipeline
                    ctx.pipeline().addFirst("mcloud_ssl", ssl.newHandler(ctx.alloc(), endpoint.host(), endpoint.port()));
                }
            }

            // Place the WebSocket handshake handler immediately after SSL (or first if no SSL)
            ctx.pipeline().addAfter(
                    ctx.pipeline().get("mcloud_ssl") != null ? "mcloud_ssl" : null,
                    "mcloud_client_handshake",
                    new MCloudClientHandshakeHandler(endpoint.host(), endpoint.path())
            );

            // Once configured, remove this connect interceptor from the pipeline
            ctx.pipeline().remove(this);
        }

        super.connect(ctx, remoteAddress, localAddress, promise);
    }
}
