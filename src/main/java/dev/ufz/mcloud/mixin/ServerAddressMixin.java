package dev.ufz.mcloud.mixin;

import dev.ufz.mcloud.network.MCloudAddressRegistry;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(ServerAddress.class)
public class ServerAddressMixin {
    @ModifyVariable(method = "parseString(Ljava/lang/String;)Lnet/minecraft/client/multiplayer/resolver/ServerAddress;", at = @At("HEAD"), argsOnly = true)
    private static String mcloud$normalizeAddress(String rawInput) {
        return MCloudAddressRegistry.normalizeServerAddressInput(rawInput);
    }
}
