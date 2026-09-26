package dev.magicshulkerboxes;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import java.util.HashMap;
import java.util.Map;

public final class PlayerSettingsStore {
    private final Path directory;
    private final Map<UUID, JsonObject> cached = new HashMap<>();
    private final Map<UUID, IOException> failures = new HashMap<>();

    public PlayerSettingsStore(Path directory) { this.directory = directory; }

    public JsonObject read(UUID player) throws IOException {
        if (failures.containsKey(player)) throw failures.get(player);
        if (!cached.containsKey(player)) {
            try {
                cached.put(player, ConfigFile.readPreferences(directory.resolve(player + ".json")));
            } catch (IOException exception) {
                failures.put(player, exception);
                throw exception;
            }
        }
        return cached.get(player).deepCopy();
    }

    public void save(UUID player, JsonObject overrides) throws IOException {
        var validated = ConfigFile.parsePreferences(overrides.toString());
        ConfigFile.write(directory.resolve(player + ".json"), validated);
        cached.put(player, validated.deepCopy());
        failures.remove(player);
    }

    public StorageConfig resolve(UUID player, ServerConfig server) throws IOException {
        if (!server.enabled || !server.allowPlayerSettings) return server;
        var overrides = read(player);
        return overrides.isEmpty() ? server : ConfigFile.apply(server, overrides);
    }

    public void clearCache() { cached.clear(); failures.clear(); }
}
