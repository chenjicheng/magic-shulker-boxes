package dev.magicshulkerboxes.client;

import com.google.gson.JsonObject;
import dev.magicshulkerboxes.*;
import java.io.IOException;
import java.nio.file.Path;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

/** Client-thread coordinator; neither optional GUI library is referenced here. */
public final class ClientSettings {
    static final SettingsSession session = new SettingsSession();
    private static JsonObject defaults;
    private static int pendingRequest;
    private static long deadline;
    private static final SystemToast.SystemToastId TOAST = new SystemToast.SystemToastId(8000);
    private ClientSettings() {}

    public static Component text(String key, Object... args) {
        return Component.translatable("magic_shulker_boxes." + key, args);
    }
    static void notice(String key) {
        var client = Minecraft.getInstance();
        SystemToast.addOrUpdate(client.getToastManager(), TOAST, text("title"), text(key));
        if (client.player != null) client.player.displayClientMessage(text(key), false);
    }
    static void failure(IOException exception) {
        MagicShulkerBoxes.LOGGER.error("Cannot save settings / 无法保存设置", exception);
        notice("gui.failed");
    }
    static JsonObject defaults() { return defaults == null ? null : defaults.deepCopy(); }
    static Path localPath() { return FabricLoader.getInstance().getConfigDir().resolve("magic_shulker_boxes.json"); }

    public static void register() {
        ClientPlayConnectionEvents.INIT.register((handler, client) -> { session.connected(); defaults = null; });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> { session.disconnected(); defaults = null; });
        ClientPlayNetworking.registerGlobalReceiver(EditorNetwork.State.ID, (payload, context) -> {
            try {
                defaults = ConfigFile.parsePreferences(payload.defaults());
                session.policy(payload.allowed());
                SchematicRefillClient.invalidateSettings();
            } catch (IOException exception) { session.connected(); defaults = null; failure(exception); }
        });
        ClientPlayNetworking.registerGlobalReceiver(EditorNetwork.Result.ID, (payload, context) -> {
            if (!session.acknowledge(payload.request())) return;
            switch (payload.status()) {
                case EditorNetwork.SAVED -> {
                    try {
                        ConfigFile.write(MagicShulkerBoxesClient.path(), ConfigFile.parsePreferences(payload.json()));
                        SchematicRefillClient.invalidateSettings();
                        notice("gui.saved");
                    } catch (IOException exception) { failure(exception); }
                }
                case EditorNetwork.LOCKED -> { session.policy(false); notice("locked"); }
                case EditorNetwork.INVALID -> notice("invalid");
                case EditorNetwork.BUSY -> notice("gui.busy");
                default -> notice("gui.failed");
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (session.pending() && System.nanoTime() > deadline && session.acknowledge(pendingRequest)) notice("gui.timeout");
        });
    }

    static void savePersonal(long revision, JsonObject values) {
        if (!session.editable(revision)) { notice("gui.stale"); return; }
        try {
            var validated = ConfigFile.parsePreferences(values.toString());
            if (session.mode() == SettingsSession.Mode.OFFLINE) {
                ConfigFile.write(MagicShulkerBoxesClient.path(), validated);
                SchematicRefillClient.invalidateSettings();
                notice("gui.saved");
            } else if (ClientPlayNetworking.canSend(EditorNetwork.Save.ID)) {
                pendingRequest = session.beginSave(revision);
                deadline = System.nanoTime() + 10_000_000_000L;
                ClientPlayNetworking.send(new EditorNetwork.Save(pendingRequest, validated.toString()));
                notice("gui.pending");
            } else notice("gui.stale");
        } catch (IOException exception) { failure(exception); }
    }

    static void saveLocal(MinecraftServer expectedServer, long revision, JsonObject original, JsonObject values) {
        var client = Minecraft.getInstance();
        if (session.connectionRevision() != revision || client.getSingleplayerServer() != expectedServer
                || (client.getConnection() != null && expectedServer == null)) { notice("gui.stale"); return; }
        var baseline = original.deepCopy();
        var replacement = values.deepCopy();
        // Local host only: there is deliberately no network payload for administrator settings.
        Runnable save = () -> {
            try {
                var current = ConfigFile.load(localPath());
                if (!ConfigFile.json(current).equals(ConfigFile.json(ConfigFile.parseServer(baseline.toString())))) {
                    client.execute(() -> notice("gui.stale"));
                    return;
                }
                MagicShulkerBoxes.replaceConfig(ConfigFile.parseServer(replacement.toString()));
                if (expectedServer != null) SettingsNetwork.broadcastPolicy(expectedServer);
                client.execute(() -> {
                    original.entrySet().clear();
                    replacement.entrySet().forEach(entry -> original.add(entry.getKey(), entry.getValue().deepCopy()));
                    notice("gui.saved");
                });
            } catch (IOException exception) { client.execute(() -> failure(exception)); }
        };
        if (expectedServer == null) save.run(); else expectedServer.execute(save);
    }
}
