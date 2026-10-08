package dev.card.friendgroupscene;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.network.OtherClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerSkinType;
import net.minecraft.entity.player.SkinTextures;
import net.minecraft.util.AssetInfo;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;

public final class ScenePlayerEntity extends OtherClientPlayerEntity {
    private final ScenePose scenePose;
    private final SkinTextures sceneSkin;
    private double frozenX;
    private double frozenY;
    private double frozenZ;
    private float frozenYaw;
    private boolean frozen;

    public ScenePlayerEntity(ClientWorld world, GameProfile profile, ScenePose pose, Identifier skinTexture, boolean slim) {
        super(world, profile);
        this.scenePose = pose;

        // 1.21.9's one-argument TextureAssetInfo constructor rewrites the ID to
        // textures/<path>.png. Our ID already is the full resource path, so use
        // the explicit (id, texturePath) constructor instead.
        AssetInfo.TextureAssetInfo body = new AssetInfo.TextureAssetInfo(skinTexture, skinTexture);
        this.sceneSkin = SkinTextures.create(
                body,
                null,
                null,
                slim ? PlayerSkinType.SLIM : PlayerSkinType.WIDE
        );

        setNoGravity(true);
        setVelocity(Vec3d.ZERO);
    }

    public ScenePose friendgroup$getScenePose() {
        return scenePose;
    }

    public SkinTextures friendgroup$getSceneSkin() {
        return sceneSkin;
    }

    public void friendgroup$freezeAt(double x, double y, double z, float yaw) {
        frozenX = x;
        frozenY = y;
        frozenZ = z;
        frozenYaw = yaw;
        frozen = true;
        friendgroup$applyFrozenState();
    }

    private void friendgroup$applyFrozenState() {
        if (!frozen) return;

        setPosition(frozenX, frozenY, frozenZ);
        setVelocity(Vec3d.ZERO);
        setYaw(frozenYaw);
        setPitch(0.0f);

        // Keep current and previous render rotations identical. This prevents
        // the partial-tick interpolation from sweeping from an old angle to
        // the staged angle on every tick.
        this.prevYaw = frozenYaw;
        this.prevPitch = 0.0f;
        this.bodyYaw = frozenYaw;
        this.prevBodyYaw = frozenYaw;
        this.headYaw = frozenYaw;
        this.prevHeadYaw = frozenYaw;
    }

    @Override
    public void tick() {
        // OtherClientPlayerEntity must still tick so vanilla keeps its render
        // interpolation/limb bookkeeping coherent. Then snap it back to the
        // exact staged transform.
        super.tick();
        friendgroup$applyFrozenState();
    }
}
