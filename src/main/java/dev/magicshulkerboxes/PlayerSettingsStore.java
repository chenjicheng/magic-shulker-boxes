package dev.magicshulkerboxes;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
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
        ConfigFile.writePreferences(directory.resolve(player + ".json"), validated);
        cached.put(player, validated.deepCopy());
        failures.remove(player);
    }

    public StorageConfig resolve(UUID player, ServerConfig server) throws IOException {
        if (!server.allowPlayerSettings) return server;
        var overrides = read(player);
        return overrides.isEmpty() ? server : ConfigFile.apply(server, overrides);
    }

    public void clearCache() { cached.clear(); failures.clear(); }

    /** Process offline players too; a bad file is isolated and never prevents other migrations. */
    public void migrateExisting() throws IOException {
        if (Files.notExists(directory)) return;
        try (var files = Files.newDirectoryStream(directory, "*.json")) {
            for (var path : files) {
                UUID player;
                var name = path.getFileName().toString();
                try { player = UUID.fromString(name.substring(0, name.length() - 5)); }
                catch (IllegalArgumentException exception) { continue; }
                if (!name.equals(player + ".json")) continue;
                try { read(player); }
                catch (IOException exception) {
                    MagicShulkerBoxes.LOGGER.error("Cannot migrate player settings; file preserved / 无法迁移玩家设置，保留原文件: {}", path, exception);
                }
            }
        }
    }
}
