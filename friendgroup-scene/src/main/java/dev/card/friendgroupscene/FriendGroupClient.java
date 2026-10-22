package dev.card.friendgroupscene;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

public final class FriendGroupClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("add").executes(context -> {
                MinecraftClient client = MinecraftClient.getInstance();
                int count = SceneManager.add(client);
                context.getSource().sendFeedback(SceneManager.addMessage(count));
                return count > 0 ? 1 : 0;
            }));

            dispatcher.register(ClientCommandManager.literal("remove").executes(context -> {
                MinecraftClient client = MinecraftClient.getInstance();
                int count = SceneManager.remove(client);
                context.getSource().sendFeedback(Text.literal("Friend group scene removed (" + count + " local players)."));
                return 1;
            }));
        });

        ClientTickEvents.END_CLIENT_TICK.register(SceneManager::tick);
    }
}
