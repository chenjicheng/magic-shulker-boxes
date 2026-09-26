package dev.magicshulkerboxes.client;

import com.google.gson.JsonObject;
import dev.magicshulkerboxes.ConfigFile;
import dev.magicshulkerboxes.MagicShulkerBoxes;
import dev.magicshulkerboxes.Messages;
import dev.magicshulkerboxes.SettingsNetwork;
import java.io.IOException;
import java.nio.file.Files;
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
            if (Files.notExists(path())) ConfigFile.write(path(), new JsonObject());
        } catch (IOException exception) {
            MagicShulkerBoxes.LOGGER.error("Cannot create personal settings / 无法创建个人设置", exception);
        }
        ClientPlayNetworking.registerGlobalReceiver(SettingsNetwork.Policy.ID, (payload, context) -> {
            if (!payload.allowed() || !ClientPlayNetworking.canSend(SettingsNetwork.Preferences.ID)) return;
            try {
                var overrides = ConfigFile.readPreferences(path());
                ClientPlayNetworking.send(new SettingsNetwork.Preferences(overrides.toString()));
            } catch (IOException exception) {
                context.player().displayClientMessage(Messages.text(context.client().options.languageCode, "client_failed"), false);
                MagicShulkerBoxes.LOGGER.error("Cannot send personal settings / 无法同步个人设置", exception);
            }
        });
        ClientPlayNetworking.registerGlobalReceiver(SettingsNetwork.Preferences.ID, (payload, context) -> {
            try {
                // A correlated GUI reply owns persistence while that save is pending.
                if (ClientSettings.session.pending()) return;
                ConfigFile.write(path(), ConfigFile.parsePreferences(payload.json()));
                SchematicRefillClient.invalidateSettings();
            } catch (IOException exception) {
                context.player().displayClientMessage(Messages.text(context.client().options.languageCode, "client_failed"), false);
                MagicShulkerBoxes.LOGGER.error("Cannot save personal settings / 无法保存个人设置", exception);
            }
        });
    }
}
