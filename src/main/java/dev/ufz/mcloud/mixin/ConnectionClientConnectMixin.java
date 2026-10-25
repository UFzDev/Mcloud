package dev.ufz.mcloud.mixin;

import dev.ufz.mcloud.MCloudMod;
import dev.ufz.mcloud.network.MCloudAddressRegistry;
import dev.ufz.mcloud.network.MCloudClientHandshakeHandler;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import javax.net.ssl.SSLException;
import java.net.InetSocketAddress;

@Mixin(Connection.class)
public class ConnectionClientConnectMixin {
    private static SslContext cachedSslContext;

    private static synchronized SslContext getOrCreateSslContext() {
        if (cachedSslContext == null) {
            try {
                cachedSslContext = SslContextBuilder.forClient().build();
            } catch (SSLException e) {
                MCloudMod.LOGGER.error("[MCloud] Failed to create default SSL context, using insecure fallback", e);
                try {
                    cachedSslContext = SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build();
                } catch (SSLException ignored) {
                }
            }
        }
        return cachedSslContext;
    }

    /**
     * If connecting to a Cloudflare tunnel or WebSocket endpoint, redirect the port to 443.
     */
    @ModifyVariable(
            method = "connect(Ljava/net/InetSocketAddress;ZLnet/minecraft/network/Connection;)Lio/netty/channel/ChannelFuture;",
            at = @At("HEAD"),
            argsOnly = true,
            ordinal = 0
    )
    private static InetSocketAddress mcloud$redirectPort(InetSocketAddress address) {
        if (address == null) return address;
        MCloudAddressRegistry.EndpointInfo endpoint = MCloudAddressRegistry.resolveEndpoint(address);
        if (endpoint != null && endpoint.port() != address.getPort()) {
            MCloudMod.LOGGER.info("[MCloud] Routing connection for {} from port {} to Cloudflare port {}",
                    address.getHostString(), address.getPort(), endpoint.port());
            return new InetSocketAddress(address.getHostString(), endpoint.port());
        }
        return address;
    }

    /**
     * Wraps the Bootstrap ChannelInitializer so that MCloud's TLS SslHandler and
     * WebSocket Handshake Handler are guaranteed to be installed in the client pipeline.
     */
    @ModifyArg(
            method = "connect(Ljava/net/InetSocketAddress;ZLnet/minecraft/network/Connection;)Lio/netty/channel/ChannelFuture;",
            at = @At(
                    value = "INVOKE",
                    target = "Lio/netty/bootstrap/Bootstrap;handler(Lio/netty/channel/ChannelHandler;)Lio/netty/bootstrap/AbstractBootstrap;"
            ),
            index = 0
    )
    private static ChannelHandler mcloud$wrapChannelInitializer(ChannelHandler originalHandler, @org.spongepowered.asm.mixin.injection.Coerce Object addressObj) {
        return new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel ch) throws Exception {
                // First let Minecraft install its timeout, decoder, encoder, and packet_handler
                if (originalHandler instanceof ChannelInitializer<?> init) {
                    java.lang.reflect.Method m = ChannelInitializer.class.getDeclaredMethod("initChannel", Channel.class);
                    m.setAccessible(true);
                    m.invoke(init, ch);
                }

                // Now inspect if this connection is routed through an MCloud WebSocket tunnel
                if (ch.remoteAddress() instanceof InetSocketAddress remote) {
                    MCloudAddressRegistry.EndpointInfo endpoint = MCloudAddressRegistry.resolveEndpoint(remote);
                    if (endpoint != null) {
                        MCloudMod.LOGGER.info("[MCloud] Installing TLS and WebSocket tunnel handlers for {}", endpoint.host());
                        if (endpoint.useTls()) {
                            SslContext ssl = getOrCreateSslContext();
                            if (ssl != null) {
                                ch.pipeline().addFirst("mcloud_ssl", ssl.newHandler(ch.alloc(), endpoint.host(), endpoint.port()));
                            }
                        }
                        ch.pipeline().addAfter(
                                ch.pipeline().get("mcloud_ssl") != null ? "mcloud_ssl" : null,
                                "mcloud_client_handshake",
                                new MCloudClientHandshakeHandler(endpoint.host(), endpoint.path())
                        );
                    }
                }
            }
        };
    }
}
