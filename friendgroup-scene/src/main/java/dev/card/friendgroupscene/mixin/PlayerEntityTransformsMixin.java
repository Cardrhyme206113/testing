package dev.card.friendgroupscene.mixin;

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
    private void friendgroup$applyWholeBodyTilt(PlayerEntityRenderState state, MatrixStack matrices, float bodyYaw, float baseHeight, CallbackInfo ci) {
        ScenePose pose = SceneManager.poseForEntityId(state.id);
        if (pose == null) return;
        float x = pose.all().x();
        // Same skinview3d -> Minecraft basis conversion as the limb model:
        // x stays x, z changes sign.
        float z = -pose.all().z();
        if (Math.abs(x) < 1.0e-6f && Math.abs(z) < 1.0e-6f) return;
        matrices.translate(0.0f, 1.0f, 0.0f);
        if (x != 0.0f) matrices.multiply(new Quaternionf().rotationX(x));
        if (z != 0.0f) matrices.multiply(new Quaternionf().rotationZ(z));
        matrices.translate(0.0f, -1.0f, 0.0f);
    }
}
