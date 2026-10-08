package dev.card.friendgroupscene;

import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.entity.model.PlayerEntityModel;

import java.util.IdentityHashMap;
import java.util.Map;

public final class SceneModelPoseReset {
    private static final Map<PlayerEntityModel<?>, Snapshot> DEFAULTS = new IdentityHashMap<>();

    private SceneModelPoseReset() {}

    public static void restoreOrCapture(PlayerEntityModel<?> model) {
        Snapshot snapshot = DEFAULTS.get(model);
        if (snapshot == null) {
            DEFAULTS.put(model, Snapshot.capture(model));
            return;
        }
        snapshot.restore(model);
    }

    private record PartPose(
            float originX, float originY, float originZ,
            float pitch, float yaw, float roll,
            float xScale, float yScale, float zScale
    ) {
        static PartPose capture(ModelPart p) {
            return new PartPose(
                    p.originX, p.originY, p.originZ,
                    p.pitch, p.yaw, p.roll,
                    p.xScale, p.yScale, p.zScale
            );
        }

        void restore(ModelPart p) {
            p.originX = originX;
            p.originY = originY;
            p.originZ = originZ;
            p.pitch = pitch;
            p.yaw = yaw;
            p.roll = roll;
            p.xScale = xScale;
            p.yScale = yScale;
            p.zScale = zScale;
        }
    }

    private record Snapshot(
            PartPose head, PartPose body,
            PartPose leftArm, PartPose rightArm,
            PartPose leftLeg, PartPose rightLeg,
            PartPose hat, PartPose jacket,
            PartPose leftSleeve, PartPose rightSleeve,
            PartPose leftPants, PartPose rightPants
    ) {
        static Snapshot capture(PlayerEntityModel<?> m) {
            return new Snapshot(
                    PartPose.capture(m.head), PartPose.capture(m.body),
                    PartPose.capture(m.leftArm), PartPose.capture(m.rightArm),
                    PartPose.capture(m.leftLeg), PartPose.capture(m.rightLeg),
                    PartPose.capture(m.hat), PartPose.capture(m.jacket),
                    PartPose.capture(m.leftSleeve), PartPose.capture(m.rightSleeve),
                    PartPose.capture(m.leftPants), PartPose.capture(m.rightPants)
            );
        }

        void restore(PlayerEntityModel<?> m) {
            head.restore(m.head);
            body.restore(m.body);
            leftArm.restore(m.leftArm);
            rightArm.restore(m.rightArm);
            leftLeg.restore(m.leftLeg);
            rightLeg.restore(m.rightLeg);
            hat.restore(m.hat);
            jacket.restore(m.jacket);
            leftSleeve.restore(m.leftSleeve);
            rightSleeve.restore(m.rightSleeve);
            leftPants.restore(m.leftPants);
            rightPants.restore(m.rightPants);
        }
    }
}
