package dev.card.friendgroupscene.mixin;

import dev.card.friendgroupscene.ScenePlayerEntity;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.PlayerLikeEntity;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

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

    // Scene actors and the local first-person arm use the same renderer/model
    // instances. Vanilla's arm-only path doesn't reset every yaw/roll field,
    // so explicitly neutralize those fields before drawing the real hand.
    @Inject(method = "renderRightArm", at = @At("HEAD"))
    private void friendgroup$resetBeforeRightHand(MatrixStack matrices, OrderedRenderCommandQueue queue, int light,
                                                   Identifier skinTexture, boolean sleeveVisible, CallbackInfo ci) {
        PlayerEntityModel model = ((PlayerEntityRenderer) (Object) this).getModel();
        neutralArm(model.rightArm);
        neutralArm(model.rightSleeve);
    }

    @Inject(method = "renderLeftArm", at = @At("HEAD"))
    private void friendgroup$resetBeforeLeftHand(MatrixStack matrices, OrderedRenderCommandQueue queue, int light,
                                                  Identifier skinTexture, boolean sleeveVisible, CallbackInfo ci) {
        PlayerEntityModel model = ((PlayerEntityRenderer) (Object) this).getModel();
        neutralArm(model.leftArm);
        neutralArm(model.leftSleeve);
    }

    private static void neutralArm(ModelPart part) {
        part.pitch = 0.0f;
        part.yaw = 0.0f;
        part.roll = 0.0f;
        part.xScale = 1.0f;
        part.yScale = 1.0f;
        part.zScale = 1.0f;
    }
}
