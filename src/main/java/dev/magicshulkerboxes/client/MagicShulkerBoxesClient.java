package dev.magicshulkerboxes.client;

import dev.magicshulkerboxes.ConfigFile;
import dev.magicshulkerboxes.MagicShulkerBoxes;
import dev.magicshulkerboxes.SettingsNetwork;
import java.io.IOException;
import java.nio.file.Path;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;

/** This entrypoint is loaded only on physical clients; dedicated servers never resolve client classes. */
public final class MagicShulkerBoxesClient implements ClientModInitializer {
    static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("magic_shulker_boxes-client.json");
    }

    @Override
    public void onInitializeClient() {
        ClientSettings.register();
        SchematicRefillClient.register();
        try {
            ConfigFile.readPreferences(path());
        } catch (IOException exception) {
            MagicShulkerBoxes.LOGGER.error("Cannot create personal settings / 无法创建个人设置", exception);
        }
        ClientPlayNetworking.registerGlobalReceiver(SettingsNetwork.Policy.ID, (payload, context) -> {
            ClientSettings.synchronize(payload.allowed());
        });
        ClientPlayNetworking.registerGlobalReceiver(SettingsNetwork.Preferences.ID, (payload, context) -> {
            // A correlated GUI reply owns reconciliation while a save or query is pending.
            if (!ClientSettings.session.pending()) ClientSettings.receivePreferences(payload.json());
        });
    }
}
