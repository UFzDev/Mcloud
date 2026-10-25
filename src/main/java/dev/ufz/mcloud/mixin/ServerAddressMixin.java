package dev.ufz.mcloud.mixin;

import dev.ufz.mcloud.network.MCloudAddressRegistry;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerAddress.class)
public class ServerAddressMixin {

    @ModifyVariable(
            method = "parseString(Ljava/lang/String;)Lnet/minecraft/client/multiplayer/resolver/ServerAddress;",
            at = @At("HEAD"),
            argsOnly = true
    )
    private static String mcloud$normalizeAddress(String rawInput) {
        return MCloudAddressRegistry.normalizeServerAddressInput(rawInput);
    }

    @Inject(
            method = "isValidAddress(Ljava/lang/String;)Z",
            at = @At("HEAD"),
            cancellable = true
    )
    private static void mcloud$allowWebSocketUrls(String address, CallbackInfoReturnable<Boolean> cir) {
        if (address == null) return;
        String clean = MCloudAddressRegistry.normalizeServerAddressInput(address);
        if (clean != null && !clean.equals(address)) {
            cir.setReturnValue(true);
        }
    }
}
