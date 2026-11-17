package dev.overtoneparty.client;

import dev.overtoneparty.client.gui.PartyScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

public final class PartyClientInit implements ClientModInitializer {
    public static KeyBinding openKey;

    @Override
    public void onInitializeClient() {
        PartyConfig.load();

        try {
            PartyClientNet.registerClient();
        } catch (Throwable t) {
            dev.overtoneparty.OvertoneParty.LOGGER.error("Сетевые каналы Overtone Party не зарегистрированы", t);
        }

        openKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.overtone_party.open",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_P,
                "key.categories.overtone_party"));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openKey.wasPressed()) {
                client.setScreen(new PartyScreen());
            }
            PartyClient.tick(client);
        });

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> PartyClient.onJoin());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> PartyClient.onDisconnect());
    }
}
