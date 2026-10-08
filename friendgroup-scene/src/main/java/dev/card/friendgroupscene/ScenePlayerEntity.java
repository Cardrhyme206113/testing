package dev.card.friendgroupscene;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.network.OtherClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerSkinType;
import net.minecraft.entity.player.SkinTextures;
import net.minecraft.util.AssetInfo;
import net.minecraft.util.Identifier;

public final class ScenePlayerEntity extends OtherClientPlayerEntity {
    private final ScenePose scenePose;
    private final SkinTextures sceneSkin;

    public ScenePlayerEntity(ClientWorld world, GameProfile profile, ScenePose pose, Identifier skinTexture, boolean slim) {
        super(world, profile);
        this.scenePose = pose;
        this.sceneSkin = SkinTextures.create(
                new AssetInfo.TextureAssetInfo(skinTexture),
                null,
                null,
                slim ? PlayerSkinType.SLIM : PlayerSkinType.WIDE
        );
        setNoGravity(true);
    }

    public ScenePose friendgroup$getScenePose() {
        return scenePose;
    }

    public SkinTextures friendgroup$getSceneSkin() {
        return sceneSkin;
    }

    @Override
    public void tick() {
        // intentionally frozen
    }
}
