package dev.card.friendgroupscene.mixin;

import dev.card.friendgroupscene.Rot;
import dev.card.friendgroupscene.SceneManager;
import dev.card.friendgroupscene.ScenePose;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import org.joml.Matrix3f;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/*
 * skinview3d 3.4.2:
 *   Three.js Euler order = XYZ.
 *
 * Minecraft 1.21.9 ModelPart:
 *   applyTransform() uses Quaternionf.rotationZYX(roll, yaw, pitch).
 *
 * Merely copying/sign-flipping the three Euler numbers is therefore wrong
 * whenever more than one axis is non-zero. The seated legs expose this badly:
 * ~90 degree pitch combined with yaw produces a visibly displaced foot.
 *
 * Convert the skinview XYZ matrix into the Minecraft model coordinate basis,
 * then decompose the resulting matrix as the ZYX Euler angles ModelPart wants.
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

        // 1.21.9 second-skin parts are children of these parts:
        // hat->head, jacket->body, sleeves->arms, pants->legs.
        // Never copy parent rotations onto them; they inherit automatically.
    }

    private static void applySkinviewRotation(ModelPart part, Rot rot) {
        // Coordinate-basis conversion between skinview's model coordinates and
        // Minecraft's pre-render model coordinates:
        //   (x, y, z) -> (x, -y, -z)
        //
        // Keep skinview's XYZ composition, then ask JOML for the equivalent
        // ZYX Euler tuple consumed by ModelPart.applyTransform().
        Matrix3f exact = new Matrix3f().rotationXYZ(rot.x(), -rot.y(), -rot.z());
        Vector3f mcEuler = exact.getEulerAnglesZYX(new Vector3f());
        part.pitch = mcEuler.x;
        part.yaw = mcEuler.y;
        part.roll = mcEuler.z;
    }
}
