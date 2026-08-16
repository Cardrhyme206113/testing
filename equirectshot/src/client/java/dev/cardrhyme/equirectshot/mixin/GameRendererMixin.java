package dev.cardrhyme.equirectshot.mixin;

import dev.cardrhyme.equirectshot.CaptureManager;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Inject(method = "extract", at = @At("HEAD"))
    private void equirectshot$beforeExtract(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
        CaptureManager.INSTANCE.beforeExtract(Minecraft.getInstance());
    }

    @Inject(method = "extract", at = @At("TAIL"))
    private void equirectshot$afterExtract(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
        CaptureManager.INSTANCE.afterExtract(Minecraft.getInstance());
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void equirectshot$afterRender(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
        CaptureManager.INSTANCE.afterRender(Minecraft.getInstance());
    }
}
