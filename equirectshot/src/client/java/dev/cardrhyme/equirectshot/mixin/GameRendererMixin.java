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
    /*
     * Minecraft 26.2 updates the real render Camera in GameRenderer.update(),
     * before GameRenderer.extract() copies that camera into render state.
     * Rotate the camera entity only for that update call, then immediately
     * restore it so the server/player state is not left facing each cube face.
     */
    @Inject(method = "update", at = @At("HEAD"))
    private void equirectshot$beforeCameraUpdate(DeltaTracker deltaTracker, CallbackInfo ci) {
        CaptureManager.INSTANCE.beforeExtract(Minecraft.getInstance());
    }

    @Inject(method = "update", at = @At("TAIL"))
    private void equirectshot$afterCameraUpdate(DeltaTracker deltaTracker, CallbackInfo ci) {
        CaptureManager.INSTANCE.afterExtract(Minecraft.getInstance());
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void equirectshot$afterRender(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
        CaptureManager.INSTANCE.afterRender(Minecraft.getInstance());
    }
}
