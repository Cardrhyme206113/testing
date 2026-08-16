package dev.cardrhyme.equirectshot.mixin;

import dev.cardrhyme.equirectshot.CaptureManager;
import dev.cardrhyme.equirectshot.EquirectShotClient;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(LevelExtractor.class)
public abstract class LevelExtractorMixin {
    /*
     * Vanilla skips the camera entity while in first person. During an
     * EquirectShot capture we want the local player body included in the
     * panorama, so make this one visibility check behave like a detached
     * camera. This only affects entity extraction for the screenshot frames;
     * it does not change the actual camera position or server/player state.
     */
    @Redirect(
            method = "extractVisibleEntities",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;isDetached()Z")
    )
    private boolean equirectshot$renderCameraEntity(Camera camera) {
        if (CaptureManager.INSTANCE.isActive()
                && EquirectShotClient.CONFIG != null
                && EquirectShotClient.CONFIG.renderSelf) {
            return true;
        }
        return camera.isDetached();
    }
}
