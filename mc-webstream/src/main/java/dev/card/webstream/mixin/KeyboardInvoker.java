package dev.card.webstream.mixin;

import net.minecraft.client.Keyboard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Keyboard.class)
public interface KeyboardInvoker {
    @Invoker("onKey")
    void mcwebstream$invokeOnKey(long window, int key, int scancode, int action, int modifiers);

    @Invoker("onChar")
    void mcwebstream$invokeOnChar(long window, int codePoint, int modifiers);
}
