package dev.card.friendgroupscene;

public record SceneActor(
        double x, double y, double z, ScenePose pose, String skinFile, boolean slim, String action
) {}
