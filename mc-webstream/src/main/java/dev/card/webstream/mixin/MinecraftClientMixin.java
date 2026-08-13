package dev.card.webstream.mixin;

import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {
    @Inject(method = "onWindowFocusChanged", at = @At("HEAD"), cancellable = true)
    private void mcwebstream$keepRunningWhenUnfocused(boolean focused, CallbackInfo ci) {
        if (!focused) {
            ci.cancel();
        }
    }
}
