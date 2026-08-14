package dev.cardrhyme.cinecontrols.mixin;

import dev.cardrhyme.cinecontrols.CineControlsClient;
import dev.cardrhyme.cinecontrols.PhotoConfig;
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

    @Inject(method = "getRainLevel", at = @At("HEAD"), cancellable = true)
    private void clientPhotoControls$overrideRain(float partialTick, CallbackInfoReturnable<Float> cir) {
        if (!((Object) this instanceof ClientLevel)) {
            return;
        }

        PhotoConfig.WeatherMode weather = CineControlsClient.CONFIG.weather;
        if (weather == PhotoConfig.WeatherMode.CLEAR) {
            cir.setReturnValue(0.0f);
        } else if (weather == PhotoConfig.WeatherMode.RAIN || weather == PhotoConfig.WeatherMode.THUNDER) {
            cir.setReturnValue(1.0f);
        }
    }

    @Inject(method = "getThunderLevel", at = @At("HEAD"), cancellable = true)
    private void clientPhotoControls$overrideThunder(float partialTick, CallbackInfoReturnable<Float> cir) {
        if (!((Object) this instanceof ClientLevel)) {
            return;
        }

        PhotoConfig.WeatherMode weather = CineControlsClient.CONFIG.weather;
        if (weather == PhotoConfig.WeatherMode.CLEAR || weather == PhotoConfig.WeatherMode.RAIN) {
            cir.setReturnValue(0.0f);
        } else if (weather == PhotoConfig.WeatherMode.THUNDER) {
            cir.setReturnValue(1.0f);
        }
    }
}
