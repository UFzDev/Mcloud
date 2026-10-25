package dev.ufz.mcloud.mixin;

import dev.ufz.mcloud.MCloudMod;
import dev.ufz.mcloud.network.MCloudAddressRegistry;
import io.netty.channel.ChannelFuture;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.net.InetSocketAddress;

@Mixin(Connection.class)
public class ConnectionClientConnectMixin {

    /**
     * If connecting to a Cloudflare tunnel or WebSocket endpoint, redirect the port to 443.
     */
    @ModifyVariable(
            method = "connect(Ljava/net/InetSocketAddress;ZLnet/minecraft/network/Connection;)Lio/netty/channel/ChannelFuture;",
            at = @At("HEAD"),
            argsOnly = true,
            ordinal = 0
    )
    private static InetSocketAddress mcloud(InetSocketAddress address) {
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
     * Store the resolved EndpointInfo onto the returned Channel's attributes so that
     * pipeline handlers or ChannelInitializer can access it even if remoteAddress() is null initially.
     */
    @Inject(
            method = "connect(Ljava/net/InetSocketAddress;ZLnet/minecraft/network/Connection;)Lio/netty/channel/ChannelFuture;",
            at = @At("RETURN")
    )
    private static void mcloud(InetSocketAddress address, boolean useEpoll, Connection connection, CallbackInfoReturnable<ChannelFuture> cir) {
        if (address == null) return;
        MCloudAddressRegistry.EndpointInfo endpoint = MCloudAddressRegistry.resolveEndpoint(address);
        if (endpoint != null) {
            ChannelFuture future = cir.getReturnValue();
            if (future != null && future.channel() != null) {
                future.channel().attr(MCloudAddressRegistry.ENDPOINT_ATTR).set(endpoint);
                MCloudMod.LOGGER.info("[MCloud] Tagged channel with endpoint info for {}:{}", endpoint.host(), endpoint.port());
            }
        }
    }
}
