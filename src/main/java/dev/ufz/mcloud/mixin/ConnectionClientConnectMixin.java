package dev.ufz.mcloud.mixin;

import dev.ufz.mcloud.MCloudMod;
import dev.ufz.mcloud.network.MCloudAddressRegistry;
import dev.ufz.mcloud.network.MCloudClientHandshakeHandler;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import javax.net.ssl.SSLException;
import java.net.InetSocketAddress;

@Mixin(Connection.class)
public class ConnectionClientConnectMixin {
    private static SslContext cachedSslContext;

    private static synchronized SslContext getOrCreateSslContext() {
        if (cachedSslContext == null) {
            try {
                // Trust standard CA roots, and fallback to system trust manager
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
     * If the target is an MCloud / Cloudflare tunnel, redirect the TCP connection port
     * from vanilla 25565 to 443 (or configured port).
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
}
