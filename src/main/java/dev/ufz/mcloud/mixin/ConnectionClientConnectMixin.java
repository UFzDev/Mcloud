package dev.ufz.mcloud.mixin;

import dev.ufz.mcloud.MCloudMod;
import dev.ufz.mcloud.network.MCloudAddressRegistry;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

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
