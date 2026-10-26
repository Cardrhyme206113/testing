package dev.cardrhyme.equirectshot.mixin;

import dev.cardrhyme.equirectshot.CaptureManager;
import dev.cardrhyme.equirectshot.EquirectShotClient;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.LevelRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
    @Shadow
    protected abstract EntityRenderState extractEntity(Entity entity, float partialTick);

    @Inject(method = "extractVisibleEntities", at = @At("HEAD"))
    private void equirectshot$includeCameraEntity(Camera camera, Frustum frustum, DeltaTracker deltaTracker, LevelRenderState levelRenderState, CallbackInfo ci) {
        if (!CaptureManager.INSTANCE.isActive() || EquirectShotClient.CONFIG == null || !EquirectShotClient.CONFIG.renderSelf || camera.isDetached()) return;
        Entity entity = camera.getEntity();
        if (entity == null) return;
        float partialTick = deltaTracker.getGameTimeDeltaPartialTick(true);
        levelRenderState.entityRenderStates.add(extractEntity(entity, partialTick));
    }
}
