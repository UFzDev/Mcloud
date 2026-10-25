package dev.ufz.mcloud.mixin;

import dev.ufz.mcloud.MCloudMod;
import dev.ufz.mcloud.network.MCloudAddressRegistry;
import net.minecraft.client.multiplayer.resolver.ResolvedServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerNameResolver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Optional;

@Mixin(ServerNameResolver.class)
public class ServerNameResolverMixin {

    @Inject(
            method = "resolveAddress(Lnet/minecraft/client/multiplayer/resolver/ServerAddress;)Ljava/util/Optional;",
            at = @At("HEAD"),
            cancellable = true
    )
    private void mcloud(ServerAddress serverAddress, CallbackInfoReturnable<Optional<ResolvedServerAddress>> cir) {
        if (serverAddress == null) return;

        String host = serverAddress.getHost();
        if (MCloudAddressRegistry.isCloudflareTunnelDomain(host)) {
            MCloudAddressRegistry.EndpointInfo endpoint = MCloudAddressRegistry.resolveEndpoint(
                    new InetSocketAddress(host, serverAddress.getPort())
            );
            int targetPort = endpoint != null ? endpoint.port() : 443;
            String path = endpoint != null ? endpoint.path() : "/mcloud";

            try {
                // Resolve the DNS hostname to its Cloudflare Edge IP address
                InetAddress ip = InetAddress.getByName(host);
                MCloudAddressRegistry.EndpointInfo finalEndpoint = new MCloudAddressRegistry.EndpointInfo(host, targetPort, true, path);
                MCloudAddressRegistry.mapIpToEndpoint(ip.getHostAddress(), finalEndpoint);

                InetSocketAddress socketAddress = new InetSocketAddress(ip, targetPort);

                MCloudMod.LOGGER.info("[MCloud] Resolved Cloudflare tunnel {} -> {} on port {}", host, ip.getHostAddress(), targetPort);

                cir.setReturnValue(Optional.of(ResolvedServerAddress.from(socketAddress)));
            } catch (Exception e) {
                MCloudMod.LOGGER.error("[MCloud] Failed to resolve Cloudflare DNS for {}", host, e);
            }
        }
    }
}
