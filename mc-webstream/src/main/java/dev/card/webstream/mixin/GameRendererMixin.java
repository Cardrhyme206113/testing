package dev.card.webstream.mixin;

import dev.card.webstream.McWebStreamClient;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Inject(method = "render", at = @At("RETURN"))
    private void mcwebstream$captureFinalFrame(float tickDelta, long startTime, boolean tick, CallbackInfo ci) {
        McWebStreamClient.captureBeautyFrame();
    }
}
