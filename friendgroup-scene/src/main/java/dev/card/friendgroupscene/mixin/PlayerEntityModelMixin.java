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
    @Inject(method = "setAngles(Lnet/minecraft/client/render/entity/state/PlayerEntityRenderState;)V", at = @At("TAIL"))
    private void friendgroup$applyHardcodedPose(PlayerEntityRenderState state, CallbackInfo ci) {
        ScenePose pose = SceneManager.poseForEntityId(state.id);
        if (pose == null) return;
        PlayerEntityModel model = (PlayerEntityModel) (Object) this;
        apply(model.head, pose.head());
        apply(model.body, pose.body());
        apply(model.leftArm, pose.leftArm());
        apply(model.rightArm, pose.rightArm());
        apply(model.leftLeg, pose.leftLeg());
        apply(model.rightLeg, pose.rightLeg());
        copy(model.hat, model.head);
        copy(model.jacket, model.body);
        copy(model.leftSleeve, model.leftArm);
        copy(model.rightSleeve, model.rightArm);
        copy(model.leftPants, model.leftLeg);
        copy(model.rightPants, model.rightLeg);
    }

    private static void apply(ModelPart part, Rot rot) {
        part.pitch = rot.x();
        part.yaw = -rot.y();
        part.roll = rot.z();
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
