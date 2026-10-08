package dev.card.friendgroupscene.mixin;

import dev.card.friendgroupscene.ScenePlayerEntity;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.entity.PlayerLikeEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/*
 * Keep this mixin limited to render-state data. Do not mutate the shared
 * PlayerEntityModel from renderRightArm/renderLeftArm: Minecraft 1.21.9's
 * renderArm() already resets the requested arm, and the sleeve is a child of
 * that arm. Leaving the child at its default local transform also makes
 * 3D Skin Layers-style injected meshes follow first-person arms correctly.
 */
@Mixin(PlayerEntityRenderer.class)
public abstract class PlayerEntityRendererMixin {
    @Inject(method = "updateRenderState(Lnet/minecraft/entity/PlayerLikeEntity;Lnet/minecraft/client/render/entity/state/PlayerEntityRenderState;F)V", at = @At("TAIL"))
    private void friendgroup$injectSceneSkin(PlayerLikeEntity entity, PlayerEntityRenderState state, float tickProgress, CallbackInfo ci) {
        if (!(entity instanceof ScenePlayerEntity scene)) return;
        state.skinTextures = scene.friendgroup$getSceneSkin();
        state.hatVisible = true;
        state.jacketVisible = true;
        state.leftSleeveVisible = true;
        state.rightSleeveVisible = true;
        state.leftPantsLegVisible = true;
        state.rightPantsLegVisible = true;
        state.capeVisible = false;
        state.playerName = null;
        state.applyFlyingRotation = false;
        state.glidingTicks = 0.0f;
    }
}
