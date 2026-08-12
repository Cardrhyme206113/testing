package dev.card.webstream;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class SemanticStateTracker {
    static final int SIZE = 8;
    private static final int HALF = SIZE / 2;

    private final Map<Long, BlockFingerprint> blocks = new HashMap<>();
    private final Map<Integer, EntityFingerprint> entities = new HashMap<>();
    private long sequence;
    private BlockPos lastCenter;
    private boolean forceFull = true;

    JsonObject sample(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        ClientWorld world = client.world;
        if (player == null || world == null) return null;

        BlockPos center = player.getBlockPos();
        boolean centerChanged = lastCenter == null || !lastCenter.equals(center);
        boolean full = forceFull;
        forceFull = false;
        lastCenter = center.toImmutable();

        JsonObject root = new JsonObject();
        root.addProperty("type", "state");
        root.addProperty("seq", ++sequence);
        root.addProperty("timeNs", System.nanoTime());
        root.addProperty("full", full);
        root.add("center", vec3i(center.getX(), center.getY(), center.getZ()));

        JsonObject camera = new JsonObject();
        Vec3d cameraPos = client.gameRenderer.getCamera().getPos();
        camera.addProperty("x", cameraPos.x);
        camera.addProperty("y", cameraPos.y);
        camera.addProperty("z", cameraPos.z);
        camera.addProperty("yaw", client.gameRenderer.getCamera().getYaw());
        camera.addProperty("pitch", client.gameRenderer.getCamera().getPitch());
        root.add("camera", camera);

        Vec3d velocity = player.getVelocity();
        JsonObject playerObj = new JsonObject();
        playerObj.addProperty("x", player.getX());
        playerObj.addProperty("y", player.getY());
        playerObj.addProperty("z", player.getZ());
        playerObj.addProperty("vx", velocity.x);
        playerObj.addProperty("vy", velocity.y);
        playerObj.addProperty("vz", velocity.z);
        playerObj.addProperty("yaw", player.getYaw());
        playerObj.addProperty("pitch", player.getPitch());
        playerObj.addProperty("onGround", player.isOnGround());
        root.add("player", playerObj);

        JsonArray changedBlocks = new JsonArray();
        Set<Long> visiblePositions = new HashSet<>(SIZE * SIZE * SIZE * 2);
        int minX = center.getX() - HALF;
        int minY = center.getY() - HALF;
        int minZ = center.getZ() - HALF;
        BlockPos.Mutable cursor = new BlockPos.Mutable();

        for (int dx = 0; dx < SIZE; dx++) for (int dy = 0; dy < SIZE; dy++) for (int dz = 0; dz < SIZE; dz++) {
            int x = minX + dx, y = minY + dy, z = minZ + dz;
            cursor.set(x, y, z);
            long packed = cursor.asLong();
            visiblePositions.add(packed);
            BlockState state = world.getBlockState(cursor);
            BlockFingerprint next = BlockFingerprint.of(state);
            BlockFingerprint old = blocks.get(packed);
            if (full || old == null || !old.equals(next)) changedBlocks.add(blockJson(x, y, z, next));
            blocks.put(packed, next);
        }

        if (centerChanged || full) blocks.keySet().removeIf(pos -> !visiblePositions.contains(pos));
        root.add("blocks", changedBlocks);
        root.add("entities", sampleEntities(world, player, center, full));
        return root;
    }

    void reset() {
        forceFull = true;
        blocks.clear();
        entities.clear();
        lastCenter = null;
    }

    private JsonObject sampleEntities(ClientWorld world, ClientPlayerEntity player, BlockPos center, boolean full) {
        Box box = new Box(center.getX()-HALF, center.getY()-HALF, center.getZ()-HALF,
                center.getX()+HALF, center.getY()+HALF, center.getZ()+HALF);
        List<Entity> nearby = world.getOtherEntities(player, box);
        Map<Integer, EntityFingerprint> next = new HashMap<>();
        JsonArray upsert = new JsonArray();
        for (Entity entity : nearby) {
            EntityFingerprint fp = EntityFingerprint.of(entity);
            next.put(entity.getId(), fp);
            EntityFingerprint old = entities.get(entity.getId());
            if (full || old == null || !old.approximatelyEquals(fp)) {
                JsonObject e = new JsonObject();
                e.addProperty("id", entity.getId());
                e.addProperty("uuid", entity.getUuidAsString());
                e.addProperty("typeId", Registries.ENTITY_TYPE.getId(entity.getType()).toString());
                e.addProperty("x", fp.x); e.addProperty("y", fp.y); e.addProperty("z", fp.z);
                e.addProperty("yaw", fp.yaw); e.addProperty("pitch", fp.pitch);
                e.addProperty("vx", fp.vx); e.addProperty("vy", fp.vy); e.addProperty("vz", fp.vz);
                Box bb = entity.getBoundingBox();
                e.addProperty("w", bb.maxX - bb.minX);
                e.addProperty("h", bb.maxY - bb.minY);
                e.addProperty("d", bb.maxZ - bb.minZ);
                upsert.add(e);
            }
        }
        JsonArray remove = new JsonArray();
        for (Integer oldId : entities.keySet()) if (!next.containsKey(oldId)) remove.add(oldId);
        entities.clear(); entities.putAll(next);
        JsonObject delta = new JsonObject();
        delta.add("upsert", upsert); delta.add("remove", remove);
        return delta;
    }

    private static JsonObject blockJson(int x, int y, int z, BlockFingerprint fp) {
        JsonObject b = new JsonObject();
        b.addProperty("x", x); b.addProperty("y", y); b.addProperty("z", z);
        b.addProperty("id", fp.blockId); b.addProperty("state", fp.stateString); b.addProperty("air", fp.air);
        return b;
    }

    private static JsonArray vec3i(int x, int y, int z) {
        JsonArray arr = new JsonArray(); arr.add(x); arr.add(y); arr.add(z); return arr;
    }

    private record BlockFingerprint(String blockId, String stateString, boolean air) {
        static BlockFingerprint of(BlockState state) {
            return new BlockFingerprint(Registries.BLOCK.getId(state.getBlock()).toString(), state.toString(), state.isAir());
        }
    }

    private record EntityFingerprint(double x, double y, double z, float yaw, float pitch, double vx, double vy, double vz) {
        static EntityFingerprint of(Entity e) {
            Vec3d v = e.getVelocity();
            return new EntityFingerprint(e.getX(), e.getY(), e.getZ(), e.getYaw(), e.getPitch(), v.x, v.y, v.z);
        }
        boolean approximatelyEquals(EntityFingerprint o) {
            return near(x,o.x,.001)&&near(y,o.y,.001)&&near(z,o.z,.001)&&near(yaw,o.yaw,.05)&&near(pitch,o.pitch,.05)
                    &&near(vx,o.vx,.001)&&near(vy,o.vy,.001)&&near(vz,o.vz,.001);
        }
        private static boolean near(double a,double b,double e){ return Math.abs(a-b)<=e; }
    }
}
