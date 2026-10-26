package dev.card.parallaxcapture.mixin;

import dev.card.parallaxcapture.ParallaxCaptureClient;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {
    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/GameRenderer;render(FJZ)V"))
    private void parallaxcapture$preRender(boolean tick, CallbackInfo ci) {
        ParallaxCaptureClient.onPreRender();
    }

    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/GameRenderer;render(FJZ)V", shift = At.Shift.AFTER))
    private void parallaxcapture$postRender(boolean tick, CallbackInfo ci) {
        ParallaxCaptureClient.onPostRender();
    }
}
