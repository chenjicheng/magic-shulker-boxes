package dev.magicshulkerboxes.client;

import dev.magicshulkerboxes.ConfigFile;
import dev.magicshulkerboxes.StorageConfig;
import java.io.IOException;

/** Shared effective client settings for optional refill integrations. The server verifies them again. */
final class RefillSettings {
    private static StorageConfig cached;
    private RefillSettings() {}
    static void invalidate() {
        cached = null;
        var player = net.minecraft.client.Minecraft.getInstance().player;
        if (player != null) player.getInventory().setChanged();
    }
    static StorageConfig get() {
        if (cached != null) return cached;
        cached = new StorageConfig();
        var defaults = ClientSettings.defaults();
        if (defaults != null) cached = ConfigFile.apply(cached, defaults);
        if (ClientSettings.session.mode() == SettingsSession.Mode.ALLOWED || defaults == null) {
            try { cached = ConfigFile.apply(cached, ClientSettings.preferences()); }
            catch (IOException exception) {
                cached.schematicRefill = false; cached.ipnRefill = false; cached.craftRefill = false; ClientSettings.failure(exception);
            }
        }
        return cached;
    }
}
