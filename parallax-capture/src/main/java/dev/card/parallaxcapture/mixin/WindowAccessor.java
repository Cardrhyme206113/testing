package dev.card.parallaxcapture.mixin;

import net.minecraft.client.util.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Window.class)
public interface WindowAccessor {
    @Accessor("width") void parallaxcapture$setWidth(int width);
    @Accessor("height") void parallaxcapture$setHeight(int height);
    @Accessor("framebufferWidth") void parallaxcapture$setFramebufferWidth(int framebufferWidth);
    @Accessor("framebufferHeight") void parallaxcapture$setFramebufferHeight(int framebufferHeight);
}
