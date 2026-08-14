package dev.cardrhyme.cinecontrols.mixin;

import dev.cardrhyme.cinecontrols.CineControlsClient;
import net.minecraft.client.ClientClockManager;
import net.minecraft.core.Holder;
import net.minecraft.world.clock.WorldClock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Minecraft 26.2 replaced Level#getDayTime with the WorldClock system.
 * Intercept the client clock read so environment timelines (sky/sun/moon etc.)
 * see the configured screenshot time without modifying server state.
 */
@Mixin(ClientClockManager.class)
public abstract class ClientClockManagerMixin {
    @Inject(method = "getTotalTicks", at = @At("HEAD"), cancellable = true)
    private void clientPhotoControls$overrideClockTime(
            Holder<WorldClock> definition,
            CallbackInfoReturnable<Long> cir) {
        if (CineControlsClient.CONFIG.overrideTime) {
            cir.setReturnValue((long) CineControlsClient.CONFIG.timeOfDay);
        }
    }
}
