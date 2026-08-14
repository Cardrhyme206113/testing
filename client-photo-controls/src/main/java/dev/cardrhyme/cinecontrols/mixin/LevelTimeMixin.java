package dev.cardrhyme.cinecontrols.mixin;

import dev.cardrhyme.cinecontrols.CineControlsClient;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Level.class)
public abstract class LevelTimeMixin {
    @Inject(method = "getDayTime", at = @At("HEAD"), cancellable = true)
    private void clientPhotoControls$overrideDayTime(CallbackInfoReturnable<Long> cir) {
        if ((Object) this instanceof ClientLevel && CineControlsClient.CONFIG.overrideTime) {
            cir.setReturnValue((long) CineControlsClient.CONFIG.timeOfDay);
        }
    }
}
