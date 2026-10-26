package dev.card.parallaxcapture;

import dev.card.parallaxcapture.mixin.WindowAccessor;
import net.minecraft.client.MinecraftClient;

public final class MinecraftInterface {
    private MinecraftInterface() {}

    public static void resize(int width, int height) {
        MinecraftClient client = MinecraftClient.getInstance();
        WindowAccessor w = (WindowAccessor) (Object) client.getWindow();
        w.parallaxcapture$setWidth(width);
        w.parallaxcapture$setHeight(height);
        w.parallaxcapture$setFramebufferWidth(width);
        w.parallaxcapture$setFramebufferHeight(height);
        client.onResolutionChanged();
    }
}
