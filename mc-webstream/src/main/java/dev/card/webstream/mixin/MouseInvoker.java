package dev.card.webstream.mixin;

import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Mouse.class)
public interface MouseInvoker {
    @Invoker("onCursorPos")
    void mcwebstream$invokeOnCursorPos(long window, double x, double y);

    @Invoker("onMouseButton")
    void mcwebstream$invokeOnMouseButton(long window, int button, int action, int modifiers);

    @Invoker("onMouseScroll")
    void mcwebstream$invokeOnMouseScroll(long window, double horizontal, double vertical);
}
