package dev.cardrhyme.cinecontrols;

import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.StorageEventHandler;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public final class SodiumPhotoConfig implements ConfigEntryPoint {
    private static final Identifier TIME_OVERRIDE = Identifier.parse("client_photo_controls:override_time");
    private static final Identifier TIME_OF_DAY = Identifier.parse("client_photo_controls:time_of_day");
    private static final Identifier WEATHER = Identifier.parse("client_photo_controls:weather");

    private final StorageEventHandler storage = CineControlsClient.CONFIG::save;

    @Override
    public void registerConfigLate(ConfigBuilder builder) {
        builder.registerOwnModOptions()
                .setName("Client Photo Controls")
                .addPage(builder.createOptionPage()
                        .setName(Component.literal("Photo Controls"))
                        .addOptionGroup(builder.createOptionGroup()
                                .setName(Component.literal("Scene"))
                                .addOption(builder.createBooleanOption(TIME_OVERRIDE)
                                        .setName(Component.literal("Override Time"))
                                        .setTooltip(Component.literal("Freeze the visual time of day locally. The server is not changed."))
                                        .setStorageHandler(this.storage)
                                        .setBinding(value -> CineControlsClient.CONFIG.overrideTime = value,
                                                () -> CineControlsClient.CONFIG.overrideTime)
                                        .setDefaultValue(false))
                                .addOption(builder.createIntegerOption(TIME_OF_DAY)
                                        .setName(Component.literal("Time of Day"))
                                        .setTooltip(Component.literal("Client-only time. 0 = sunrise, 6000 = noon, 12000 = sunset, 18000 = midnight."))
                                        .setStorageHandler(this.storage)
                                        .setBinding(value -> CineControlsClient.CONFIG.timeOfDay = value,
                                                () -> CineControlsClient.CONFIG.timeOfDay)
                                        .setDefaultValue(6000)
                                        .setRange(0, 23900, 100)
                                        .setValueFormatter(SodiumPhotoConfig::formatTime))
                                .addOption(builder.createEnumOption(WEATHER, PhotoConfig.WeatherMode.class)
                                        .setName(Component.literal("Weather"))
                                        .setTooltip(Component.literal("Override rain and thunder locally, or follow the server."))
                                        .setStorageHandler(this.storage)
                                        .setBinding(value -> CineControlsClient.CONFIG.weather = value,
                                                () -> CineControlsClient.CONFIG.weather)
                                        .setDefaultValue(PhotoConfig.WeatherMode.SERVER)
                                        .setElementNameProvider(SodiumPhotoConfig::weatherName))));
    }

    private static Component formatTime(int ticks) {
        int totalMinutes = Math.floorMod((ticks * 60) / 1000 + 6 * 60, 24 * 60);
        int hour = totalMinutes / 60;
        int minute = totalMinutes % 60;
        return Component.literal(String.format("%02d:%02d  (%d)", hour, minute, ticks));
    }

    private static Component weatherName(PhotoConfig.WeatherMode mode) {
        return Component.literal(switch (mode) {
            case SERVER -> "Server";
            case CLEAR -> "Clear";
            case RAIN -> "Rain";
            case THUNDER -> "Thunderstorm";
        });
    }
}
