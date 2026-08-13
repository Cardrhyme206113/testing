package dev.cardrhyme.offlineidentity.mixin;

import dev.cardrhyme.offlineidentity.OfflineIdentityConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.RunArgs;
import net.minecraft.client.util.Session;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {
    @Shadow @Final @Mutable
    private Session session;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void offlineIdentity$replaceSession(RunArgs args, CallbackInfo ci) {
        OfflineIdentityConfig config = OfflineIdentityConfig.load();
        if (!config.enabled) return;

        Session original = this.session;
        if (original == null || original.getAccessToken() == null || original.getAccessToken().isBlank() || original.getUuidOrNull() == null) {
            System.err.println("[Offline Identity] No authenticated launcher session detected; leaving the session untouched.");
            return;
        }

        String username = config.username;
        UUID offlineUuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));

        this.session = new Session(
                username,
                offlineUuid.toString(),
                "",
                Optional.empty(),
                Optional.empty(),
                original.getAccountType()
        );

        System.out.println("[Offline Identity] Replaced authenticated session with offline identity '" + username + "' (" + offlineUuid + ").");
    }
}
