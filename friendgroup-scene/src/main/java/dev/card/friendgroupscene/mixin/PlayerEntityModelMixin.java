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

/*
 * 1.21.9 model hierarchy note:
 * hat is a CHILD of head;
 * jacket is a CHILD of body;
 * sleeves are CHILDREN of arms;
 * pants are CHILDREN of legs.
 *
 * Therefore only pose the six parent body parts. The second-skin layers inherit
 * the transform automatically. Copying the parent transform onto the child
 * applies the rotation twice and produces the detached/spiky layers seen in the
 * previous build.
 */
@Mixin(value = PlayerEntityModel.class, priority = 100)
public abstract class PlayerEntityModelMixin {
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

        // Do NOT touch hat/jacket/sleeves/pants here on 1.21.9.
        // They are child ModelParts and follow these parents automatically.
    }

    /*
     * skinview3d -> Minecraft 1.21.9 model basis:
     * pitch = +x, yaw = -y, roll = -z.
     */
    private static void applySkinviewRotation(ModelPart part, Rot rot) {
        part.pitch = rot.x();
        part.yaw = -rot.y();
        part.roll = -rot.z();
    }
}
