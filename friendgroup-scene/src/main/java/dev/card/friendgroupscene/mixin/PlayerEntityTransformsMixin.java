package dev.card.friendgroupscene.mixin;

import dev.card.friendgroupscene.Rot;
import dev.card.friendgroupscene.SceneManager;
import dev.card.friendgroupscene.ScenePose;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerEntityRenderer.class)
public abstract class PlayerEntityTransformsMixin {
    @Inject(method = "setupTransforms(Lnet/minecraft/client/render/entity/state/PlayerEntityRenderState;Lnet/minecraft/client/util/math/MatrixStack;FF)V", at = @At("TAIL"))
    private void friendgroup$applyWholeBodyPose(PlayerEntityRenderState state, MatrixStack matrices, float bodyYaw, float baseHeight, CallbackInfo ci) {
        ScenePose pose = SceneManager.poseForEntityId(state.id);
        if (pose == null) return;

        Rot all = pose.all();
        if (Math.abs(all.x()) < 1.0e-7f && Math.abs(all.y()) < 1.0e-7f && Math.abs(all.z()) < 1.0e-7f) return;

        // skinview PlayerObject rotates around its center, one block above the
        // feet in the 32-pixel-tall model. Apply the complete XYZ rotation here
        // instead of splitting Y into entity yaw and X/Z into another stage.
        matrices.translate(0.0f, 1.0f, 0.0f);
        matrices.multiply(new Quaternionf().rotationXYZ(all.x(), -all.y(), -all.z()));
        matrices.translate(0.0f, -1.0f, 0.0f);
    }
}
