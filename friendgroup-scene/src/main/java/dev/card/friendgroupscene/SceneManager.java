package dev.card.friendgroupscene;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class SceneManager {
    public static final String MOD_ID = "friendgroup_scene";
    private static final int FIRST_LOCAL_ENTITY_ID = -1_730_000_000;

    private static final List<ScenePlayerEntity> SPAWNED = new ArrayList<>();
    private static final Map<Integer, ScenePose> POSES_BY_ENTITY_ID = new HashMap<>();
    private static ClientWorld sceneWorld;

    private SceneManager() {}

    public static ScenePose poseForEntityId(int id) {
        return POSES_BY_ENTITY_ID.get(id);
    }

    public static int add(MinecraftClient client) {
        if (client.world == null || client.player == null) return 0;
        remove(client);

        ClientWorld world = client.world;
        sceneWorld = world;
        Vec3d origin = client.player.getEntityPos();
        float anchorYaw = client.player.getYaw();
        double yawRad = Math.toRadians(anchorYaw);
        double cos = Math.cos(yawRad);
        double sin = Math.sin(yawRad);

        for (int i = 0; i < SceneData.ACTORS.size(); i++) {
            SceneActor actor = SceneData.ACTORS.get(i);
            int entityId = FIRST_LOCAL_ENTITY_ID - i;
            double lx = actor.x() / SceneData.UNITS_PER_BLOCK;
            double ly = actor.y() / SceneData.UNITS_PER_BLOCK;
            double lz = actor.z() / SceneData.UNITS_PER_BLOCK;
            double rx = lx * cos - lz * sin;
            double rz = lx * sin + lz * cos;
            float actorYaw = anchorYaw - (float) Math.toDegrees(actor.pose().all().y());

            UUID uuid = UUID.nameUUIDFromBytes((MOD_ID + ":actor:" + i).getBytes(StandardCharsets.UTF_8));
            GameProfile profile = new GameProfile(uuid, "FG" + String.format("%02d", i + 1));
            Identifier skin = Identifier.of(MOD_ID, "textures/skins/" + actor.skinFile());
            ScenePlayerEntity entity = new ScenePlayerEntity(world, profile, actor.pose(), skin, actor.slim());

            entity.setId(entityId);
            entity.refreshPositionAndAngles(origin.x + rx, origin.y + ly, origin.z + rz, actorYaw, 0.0f);
            entity.friendgroup$freezeAt(origin.x + rx, origin.y + ly, origin.z + rz, actorYaw);

            POSES_BY_ENTITY_ID.put(entityId, actor.pose());
            world.addEntity(entity);
            SPAWNED.add(entity);
        }
        return SPAWNED.size();
    }

    public static int remove(MinecraftClient client) {
        int count = SPAWNED.size();
        if (sceneWorld != null) {
            for (ScenePlayerEntity entity : SPAWNED) {
                if (!entity.isRemoved()) sceneWorld.removeEntity(entity.getId(), Entity.RemovalReason.DISCARDED);
            }
        }
        SPAWNED.clear();
        POSES_BY_ENTITY_ID.clear();
        sceneWorld = null;
        return count;
    }

    public static void tick(MinecraftClient client) {
        if (sceneWorld != null && client.world != sceneWorld) {
            SPAWNED.clear();
            POSES_BY_ENTITY_ID.clear();
            sceneWorld = null;
            return;
        }
        // Each ScenePlayerEntity snaps its current + previous transforms back
        // to the staged values in tick(), preventing partial-tick yaw jitter.
    }

    public static Text addMessage(int count) {
        return count > 0
                ? Text.literal("Friend group scene added (" + count + " local players). /add replaces it; /remove clears it.")
                : Text.literal("No client world/player yet.");
    }
}
