package dev.card.friendgroupscene.mixin;

import dev.card.friendgroupscene.Rot;
import dev.card.friendgroupscene.SceneManager;
import dev.card.friendgroupscene.ScenePose;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerEntityModel.class)
public abstract class PlayerEntityModelMixin {
    @Inject(method = "setAngles(Lnet/minecraft/client/render/entity/state/PlayerEntityRenderState;)V", at = @At("HEAD"))
    private void friendgroup$clearPreviousScenePose(PlayerEntityRenderState state, CallbackInfo ci) {
        // Player renderers reuse their model instance between entities. Our
        // scene sets yaw/roll fields vanilla doesn't always overwrite, so zero
        // those staged rotations before vanilla computes the next entity.
        PlayerEntityModel model = (PlayerEntityModel) (Object) this;
        zero(model.head); zero(model.body);
        zero(model.leftArm); zero(model.rightArm);
        zero(model.leftLeg); zero(model.rightLeg);
        zero(model.hat); zero(model.jacket);
        zero(model.leftSleeve); zero(model.rightSleeve);
        zero(model.leftPants); zero(model.rightPants);
    }

    @Inject(method = "setAngles(Lnet/minecraft/client/render/entity/state/PlayerEntityRenderState;)V", at = @At("TAIL"))
    private void friendgroup$applyHardcodedPose(PlayerEntityRenderState state, CallbackInfo ci) {
        ScenePose pose = SceneManager.poseForEntityId(state.id);
        if (pose == null) return;

        PlayerEntityModel model = (PlayerEntityModel) (Object) this;
        applySkinviewRotation(model.head, pose.head());
        applySkinviewRotation(model.body, pose.body());
        applySkinviewRotation(model.leftArm, pose.leftArm());
        applySkinviewRotation(model.rightArm, pose.rightArm());
        applySkinviewRotation(model.leftLeg, pose.leftLeg());
        applySkinviewRotation(model.rightLeg, pose.rightLeg());

        copy(model.hat, model.head);
        copy(model.jacket, model.body);
        copy(model.leftSleeve, model.leftArm);
        copy(model.rightSleeve, model.rightArm);
        copy(model.leftPants, model.leftLeg);
        copy(model.rightPants, model.rightLeg);
    }

    /*
     * skinview3d and Minecraft both use XYZ-style Euler components, but the
     * player render basis differs by (x, -y, -z). Therefore:
     *   pitch = +x, yaw = -y, roll = -z
     * The previous build forgot the roll sign, which is why the arms/legs
     * appeared corkscrewed and the seated poses were visibly wrong.
     */
    private static void applySkinviewRotation(ModelPart part, Rot rot) {
        part.pitch = rot.x();
        part.yaw = -rot.y();
        part.roll = -rot.z();
    }

    private static void zero(ModelPart part) {
        part.pitch = 0.0f;
        part.yaw = 0.0f;
        part.roll = 0.0f;
        part.xScale = 1.0f;
        part.yScale = 1.0f;
        part.zScale = 1.0f;
    }

    private static void copy(ModelPart dst, ModelPart src) {
        dst.originX = src.originX;
        dst.originY = src.originY;
        dst.originZ = src.originZ;
        dst.pitch = src.pitch;
        dst.yaw = src.yaw;
        dst.roll = src.roll;
        dst.xScale = src.xScale;
        dst.yScale = src.yScale;
        dst.zScale = src.zScale;
    }
}
