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
import net.minecraft.world.level.storage.LevelResource;

/** Client-thread coordinator; neither optional GUI library is referenced here. */
public final class ClientSettings {
    static final SettingsSession session = new SettingsSession();
    private static JsonObject defaults;
    private static PreferenceSync preferences;
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
    static boolean craftingSupported() { return defaults != null && defaults.has("craftRefill"); }
    static Path localPath() { return FabricLoader.getInstance().getConfigDir().resolve("magic_shulker_boxes.json"); }
    private static PreferenceSync sync() {
        if (preferences == null) preferences = new PreferenceSync(MagicShulkerBoxesClient.path(),
                FabricLoader.getInstance().getConfigDir().resolve("magic_shulker_boxes-recovery"));
        return preferences;
    }
    static JsonObject preferences() throws IOException { return sync().values(); }
    private static String serverKey() {
        var client = Minecraft.getInstance();
        var server = client.getSingleplayerServer();
        String address = server != null ? "local:" + server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize()
                : "remote:" + (client.getCurrentServer() != null ? client.getCurrentServer().ip
                : client.getConnection().getConnection().getRemoteAddress().toString());
        return address + "/" + client.player.getUUID();
    }
    static boolean receivePreferences(String json) {
        try {
            sync().receive(serverKey(), ConfigFile.parsePreferences(json));
            return true;
        } catch (IOException exception) {
            MagicShulkerBoxes.LOGGER.error("Server preferences received but local persistence failed / 已收到服务端设置，但本地保存失败", exception);
            notice("gui.server_saved_local_failed");
            return false;
        } finally {
            SchematicRefillClient.invalidateSettings();
            session.preferencesChanged();
        }
    }
    private static void refresh() {
        if (!ClientPlayNetworking.canSend(EditorNetwork.Query.ID)) { notice("gui.recovery_unavailable"); return; }
        if (session.pending()) return;
        pendingRequest = session.beginRefresh();
        deadline = System.nanoTime() + 10_000_000_000L;
        ClientPlayNetworking.send(new EditorNetwork.Query(pendingRequest));
        notice("gui.recovering");
    }
    static void synchronize(boolean allowed) {
        // Recover before any automatic upload, including after a disconnect or process restart.
        if (sync().needsRecovery(serverKey())) { refresh(); return; }
        if (!allowed || !ClientPlayNetworking.canSend(SettingsNetwork.Preferences.ID)) return;
        try { ClientPlayNetworking.send(new SettingsNetwork.Preferences(preferences().toString())); }
        catch (IOException exception) { failure(exception); }
    }

    public static void register() {
        ClientPlayConnectionEvents.INIT.register((handler, client) -> { session.connected(); defaults = null; sync().disconnected(); });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> { session.disconnected(); defaults = null; sync().disconnected(); });
        ClientPlayNetworking.registerGlobalReceiver(EditorNetwork.State.ID, (payload, context) -> {
            try {
                defaults = ConfigFile.parsePreferences(payload.defaults());
                session.policy(payload.allowed());
                SchematicRefillClient.invalidateSettings();
            } catch (IOException exception) { session.connected(); defaults = null; failure(exception); }
        });
        ClientPlayNetworking.registerGlobalReceiver(EditorNetwork.Result.ID, (payload, context) -> {
            boolean recovering = session.recovering();
            if (!session.acknowledgeResult(payload.request(), payload.status())) return;
            switch (payload.status()) {
                case EditorNetwork.SAVED, EditorNetwork.SNAPSHOT -> {
                    if (receivePreferences(payload.json())) {
                        notice(payload.status() == EditorNetwork.SAVED ? "gui.saved" : "gui.recovered");
                    }
                }
                case EditorNetwork.LOCKED -> { session.policy(false); notice("locked"); }
                case EditorNetwork.INVALID -> notice("invalid");
                case EditorNetwork.BUSY, EditorNetwork.QUERY_BUSY -> notice("gui.busy");
                default -> notice("gui.failed");
            }
            if (payload.status() != EditorNetwork.SAVED && payload.status() != EditorNetwork.SNAPSHOT && !recovering) refresh();
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (session.pending() && System.nanoTime() > deadline && session.timeout(pendingRequest)) {
                notice("gui.timeout");
                if (ClientPlayNetworking.canSend(EditorNetwork.Query.ID)) ClientPlayNetworking.send(new EditorNetwork.Query(pendingRequest));
            }
        });
    }

    static void savePersonal(long revision, JsonObject values) {
        if (!session.editable(revision)) { notice("gui.stale"); return; }
        try {
            var validated = ConfigFile.parsePreferences(values.toString());
            if (session.mode() == SettingsSession.Mode.OFFLINE) {
                ConfigFile.writePreferences(MagicShulkerBoxesClient.path(), validated);
                SchematicRefillClient.invalidateSettings();
                notice("gui.saved");
            } else if (ClientPlayNetworking.canSend(EditorNetwork.Save.ID) && ClientPlayNetworking.canSend(EditorNetwork.Query.ID)) {
                if (sync().needsRecovery(serverKey())) { refresh(); return; }
                sync().prepareSave(serverKey());
                pendingRequest = session.beginSave(revision);
                deadline = System.nanoTime() + 10_000_000_000L;
                ClientPlayNetworking.send(new EditorNetwork.Save(pendingRequest, validated.toString()));
                notice("gui.pending");
            } else notice("gui.recovery_unavailable");
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
