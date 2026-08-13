package dev.card.webstream.mixin;

import dev.card.webstream.McWebStreamClient;
import net.minecraft.client.util.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Window.class)
public abstract class WindowMixin {
    @Inject(method = "swapBuffers", at = @At("HEAD"))
    private void mcwebstream$captureFinalBackbuffer(CallbackInfo ci) {
        Window window = (Window) (Object) this;
        McWebStreamClient.captureFinalBackbuffer(window.getFramebufferWidth(), window.getFramebufferHeight());
    }
}
