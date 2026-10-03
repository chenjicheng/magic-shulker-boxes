package dev.magicshulkerboxes;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.Strictness;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.util.Set;

public final class ConfigFile {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().setStrictness(Strictness.STRICT).create();

    private ConfigFile() {}

    public static final int MAX_PREFERENCES_LENGTH = 4096;
    private static final int CONFIG_VERSION = 2;
    private static final String VERSION_KEY = "configVersion";
    private static final String REMOVED_CARPET_SETTING = "carpetRefill";

    public static JsonObject parsePreferences(String json) throws IOException {
        if (json.length() > MAX_PREFERENCES_LENGTH) throw new IOException("Player settings exceed 4096 characters");
        return validate(json, new StorageConfig());
    }

    public static ServerConfig load(Path path) throws IOException {
        return parseServer(readDocument(path, GSON.toJsonTree(new ServerConfig()).getAsJsonObject()).toString());
    }

    public static ServerConfig parseServer(String json) throws IOException {
        return GSON.fromJson(validate(json, new ServerConfig()), ServerConfig.class);
    }

    /** Merge personal choices with server defaults after the server has allowed personal settings. */
    public static StorageConfig apply(StorageConfig defaults, JsonObject overrides) {
        var merged = GSON.toJsonTree(defaults).getAsJsonObject();
        overrides.entrySet().forEach(entry -> merged.add(entry.getKey(), entry.getValue().deepCopy()));
        return GSON.fromJson(merged, StorageConfig.class);
    }

    public static Set<String> optionNames() {
        return GSON.toJsonTree(new StorageConfig()).getAsJsonObject().keySet();
    }

    public static JsonObject options(StorageConfig config) {
        var json = GSON.toJsonTree(config).getAsJsonObject();
        json.remove("allowPlayerSettings");
        json.remove("playerEditableSettings");
        return json;
    }

    public static JsonObject editable(JsonObject values, java.util.Collection<String> names) {
        var filtered = new JsonObject();
        values.entrySet().stream().filter(entry -> names.contains(entry.getKey()))
                .forEach(entry -> filtered.add(entry.getKey(), entry.getValue().deepCopy()));
        return filtered;
    }

    public static String json(Object value) { return GSON.toJson(value); }

    public static JsonObject readPreferences(Path path) throws IOException {
        if (Files.exists(path) && Files.size(path) > MAX_PREFERENCES_LENGTH) throw new IOException("Player settings file exceeds 4096 bytes");
        return parsePreferences(readDocument(path, new JsonObject()).toString());
    }

    /** Disk metadata never enters gameplay options, GUI drafts or network payloads. */
    public static void writePreferences(Path path, JsonObject values) throws IOException {
        var validated = parsePreferences(values.toString());
        if (!Files.notExists(path)) readPreferences(path);
        writeDocument(path, validated);
    }

    public static void writeServer(Path path, ServerConfig values) throws IOException {
        var validated = parseServer(json(values));
        if (!Files.notExists(path)) load(path);
        writeDocument(path, GSON.toJsonTree(validated).getAsJsonObject());
    }

    private static void writeDocument(Path path, JsonObject values) throws IOException {
        var document = new JsonObject();
        document.addProperty(VERSION_KEY, CONFIG_VERSION);
        values.entrySet().forEach(entry -> document.add(entry.getKey(), entry.getValue().deepCopy()));
        write(path, document);
    }

    private static JsonObject readDocument(Path path, JsonObject defaults) throws IOException {
        if (Files.notExists(path)) {
            writeDocument(path, defaults);
            return defaults;
        }
        final JsonObject document;
        try {
            var parsed = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), JsonElement.class);
            if (parsed == null || !parsed.isJsonObject()) throw new IOException("Config must be a JSON object");
            document = parsed.getAsJsonObject();
        } catch (JsonParseException exception) { throw new IOException("Invalid settings JSON", exception); }
        if (document.has(VERSION_KEY)) {
            var version = document.remove(VERSION_KEY);
            if (!version.isJsonPrimitive() || !version.getAsJsonPrimitive().isNumber()) {
                throw new IOException("Unsupported configVersion; file kept unchanged: " + path);
            }
            if (version.getAsString().equals("1")) return migrateVersionOne(path, document, defaults);
            if (!version.getAsString().equals(Integer.toString(CONFIG_VERSION))) {
                throw new IOException("Unsupported configVersion; file kept unchanged: " + path);
            }
            return migrateRemovedCarpetSetting(path, document, defaults);
        }
        // 0.3.0 and earlier had no schema marker and cannot be distinguished reliably.
        // The upgrade policy deliberately resets all of them, preserving exact original bytes first.
        var backup = backupOriginal(path, ".pre-0.3.1.bak");
        writeDocument(path, defaults);
        MagicShulkerBoxes.LOGGER.warn("Reset legacy settings; backup: {} / 旧配置已重置，备份：{}", backup, backup);
        return defaults;
    }

    /** Disk-only compatibility: retire the 0.7 setting without accepting it in commands or network input. */
    private static JsonObject migrateRemovedCarpetSetting(Path path, JsonObject document, JsonObject defaults) throws IOException {
        var migrated = document.deepCopy();
        var removed = migrated.remove(REMOVED_CARPET_SETTING);
        boolean changed = removed != null;
        if (removed != null && (!removed.isJsonPrimitive() || !removed.getAsJsonPrimitive().isBoolean())) {
            throw new IOException("Retired carpetRefill setting must be true or false; file kept unchanged: " + path);
        }
        var permissions = migrated.get("playerEditableSettings");
        if (permissions != null && permissions.isJsonArray()) {
            var retained = new com.google.gson.JsonArray();
            var seen = new java.util.HashSet<String>();
            for (var name : permissions.getAsJsonArray()) {
                if (!name.isJsonPrimitive() || !name.getAsJsonPrimitive().isString() || !seen.add(name.getAsString())) {
                    throw new IOException("Invalid or duplicate editable option; file kept unchanged: " + path);
                }
                if (name.getAsString().equals(REMOVED_CARPET_SETTING)) changed = true;
                else retained.add(name.deepCopy());
            }
            migrated.add("playerEditableSettings", retained);
        }
        if (!changed) return document;
        // Validate every remaining choice before touching the file or creating its exact-byte backup.
        validate(migrated.toString(), defaults.has("allowPlayerSettings") ? new ServerConfig() : new StorageConfig());
        var backup = backupOriginal(path, ".pre-0.8.0.bak");
        writeDocument(path, migrated);
        MagicShulkerBoxes.LOGGER.info("Removed retired Carpet setting; backup: {} / 已移除旧假人设置，备份：{}", backup, backup);
        return migrated;
    }

    /** Version 1 stored the pickup switch as `enabled`; migrate the file, not the runtime schema. */
    private static JsonObject migrateVersionOne(Path path, JsonObject old, JsonObject defaults) throws IOException {
        if (old.has("pickupStorageEnabled")) {
            throw new IOException("Version 1 file contains a version 2 option; file kept unchanged: " + path);
        }
        var migrated = old.deepCopy();
        var pickup = migrated.remove("enabled");
        if (pickup != null) migrated.add("pickupStorageEnabled", pickup);
        validate(migrated.toString(), defaults.has("allowPlayerSettings") ? new ServerConfig() : new StorageConfig());
        var backup = backupOriginal(path, ".pre-0.3.2.bak");
        writeDocument(path, migrated);
        MagicShulkerBoxes.LOGGER.info("Renamed pickup setting; backup: {} / 已重命名入盒设置，备份：{}", backup, backup);
        return migrated;
    }

    private static Path backupOriginal(Path path, String suffix) throws IOException {
        var backup = path.resolveSibling(path.getFileName() + suffix);
        if (Files.notExists(backup)) Files.copy(path, backup);
        if (Files.mismatch(path, backup) != -1) throw new IOException("Conflicting migration backup; original kept: " + backup);
        return backup;
    }

    /** Replace only after a complete write, preserving the old file if writing fails. */
    public static void write(Path path, Object value) throws IOException {
        var target = path.toAbsolutePath();
        Files.createDirectories(target.getParent());
        var temporary = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temporary, json(value) + System.lineSeparator(), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static JsonObject validate(String text, StorageConfig defaults) throws IOException {
        try {
            JsonElement json = GSON.fromJson(text, JsonElement.class);
            if (json == null || !json.isJsonObject()) throw new IOException("Config must be a JSON object");
            var knownKeys = GSON.toJsonTree(defaults).getAsJsonObject().keySet();
            for (var entry : json.getAsJsonObject().entrySet()) {
                if (!knownKeys.contains(entry.getKey())) throw new IOException("Unknown config option: " + entry.getKey());
                if (entry.getKey().equals("playerEditableSettings")) {
                    if (!entry.getValue().isJsonArray()) throw new IOException("playerEditableSettings must be an array of option names");
                    var names = new java.util.HashSet<String>();
                    for (var name : entry.getValue().getAsJsonArray()) {
                        if (!name.isJsonPrimitive() || !name.getAsJsonPrimitive().isString() || !optionNames().contains(name.getAsString())
                                || !names.add(name.getAsString())) throw new IOException("Invalid or duplicate editable option: " + name);
                    }
                    continue;
                }
                if (entry.getKey().equals("makeSpaceMode")) {
                    if (!entry.getValue().isJsonPrimitive() || !entry.getValue().getAsJsonPrimitive().isString()) {
                        throw new IOException("makeSpaceMode must be DISABLED, MOVE_TO_BOX or DROP_AND_PICKUP");
                    }
                    try {
                        StorageConfig.MakeSpaceMode.valueOf(entry.getValue().getAsString());
                    } catch (IllegalArgumentException exception) {
                        throw new IOException("Unknown makeSpaceMode: " + entry.getValue().getAsString(), exception);
                    }
                    continue;
                }
                if (!entry.getValue().isJsonPrimitive() || !entry.getValue().getAsJsonPrimitive().isBoolean()) {
                    throw new IOException("Config option must be true or false: " + entry.getKey());
                }
            }
            return json.getAsJsonObject();
        } catch (JsonParseException exception) {
            throw new IOException("Invalid settings JSON", exception);
        }
    }
}
