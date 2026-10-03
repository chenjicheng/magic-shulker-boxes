package dev.magicshulkerboxes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RemovedCarpetSettingsTest {
    @TempDir Path directory;

    @Test void oldServerSettingsRemoveOnlyCarpetAndItsPermissionAfterExactBackup() throws IOException {
        var path = directory.resolve("server.json");
        var original = "{\"configVersion\":2,\"carpetRefill\":false,\"craftRefill\":false,\"includeOffhand\":true,"
                + "\"playerEditableSettings\":[\"carpetRefill\",\"ipnRefill\"]}";
        Files.writeString(path, original);
        var config = ConfigFile.load(path);
        assertFalse(config.craftRefill);
        assertTrue(config.includeOffhand);
        assertEquals(List.of("ipnRefill"), config.playerEditableSettings);
        assertFalse(ConfigFile.options(config).has("carpetRefill"));
        assertFalse(Files.readString(path).contains("carpetRefill"));
        assertEquals(original, Files.readString(directory.resolve("server.json.pre-0.8.0.bak")));
        var migrated = Files.readString(path);
        ConfigFile.load(path);
        assertEquals(migrated, Files.readString(path));
    }

    @Test void offlineAndClientPreferencesKeepOtherExplicitChoices() throws IOException {
        for (boolean oldChoice : new boolean[]{true, false}) {
            var id = UUID.randomUUID();
            var path = directory.resolve(id + ".json");
            var original = "{\"configVersion\":2,\"carpetRefill\":" + oldChoice + ",\"craftRefill\":false}";
            Files.writeString(path, original);
            var store = new PlayerSettingsStore(directory);
            store.migrateExisting();
            var preferences = store.read(id);
            assertEquals(1, preferences.size());
            assertFalse(preferences.get("craftRefill").getAsBoolean());
            assertEquals(original, Files.readString(directory.resolve(id + ".json.pre-0.8.0.bak")));
            assertEquals(preferences, ConfigFile.readPreferences(path));
        }
    }

    @Test void permissionOnlyFilesAreMigratedWithoutEnablingOtherFields() throws IOException {
        var path = directory.resolve("server.json");
        Files.writeString(path, "{\"configVersion\":2,\"playerEditableSettings\":[\"carpetRefill\"]}");
        assertTrue(ConfigFile.load(path).playerEditableSettings.isEmpty());
        assertTrue(Files.exists(directory.resolve("server.json.pre-0.8.0.bak")));
    }

    @Test void removedSettingIsRejectedAtLiveInputBoundaries() {
        assertFalse(ConfigFile.optionNames().contains("carpetRefill"));
        assertThrows(IOException.class, () -> ConfigFile.parsePreferences("{\"carpetRefill\":true}"));
        assertThrows(IOException.class, () -> ConfigFile.parseServer("{\"playerEditableSettings\":[\"carpetRefill\"]}"));
    }

    @Test void invalidFilesAndConflictingBackupsArePreserved() throws IOException {
        var path = directory.resolve("server.json");
        for (var original : List.of("{\"configVersion\":2,\"carpetRefill\":\"true\"}",
                "{\"configVersion\":2,\"carpetRefill\":true,\"ipnRefill\":\"false\"}",
                "{\"configVersion\":2,\"playerEditableSettings\":[\"carpetRefill\",\"carpetRefill\"]}",
                "{\"configVersion\":2,\"carpetRefill\":true,\"unknown\":false}")) {
            Files.writeString(path, original);
            assertThrows(IOException.class, () -> ConfigFile.load(path));
            assertEquals(original, Files.readString(path));
            assertFalse(Files.exists(directory.resolve("server.json.pre-0.8.0.bak")));
        }
        var original = "{\"configVersion\":2,\"carpetRefill\":true}";
        Files.writeString(path, original);
        var backup = directory.resolve("server.json.pre-0.8.0.bak");
        Files.writeString(backup, "different backup");
        assertThrows(IOException.class, () -> ConfigFile.load(path));
        assertEquals(original, Files.readString(path));
        assertEquals("different backup", Files.readString(backup));
    }

    @Test void matchingBackupResumesMigrationAndSavesBeforeFirstReadProtectOriginal() throws IOException {
        var path = directory.resolve("client.json");
        var original = "{\"configVersion\":2,\"carpetRefill\":true,\"ipnRefill\":false}";
        Files.writeString(path, original);
        Files.writeString(directory.resolve("client.json.pre-0.8.0.bak"), original);
        ConfigFile.writePreferences(path, ConfigFile.parsePreferences("{\"craftRefill\":false}"));
        assertEquals(original, Files.readString(directory.resolve("client.json.pre-0.8.0.bak")));
        assertFalse(ConfigFile.readPreferences(path).get("craftRefill").getAsBoolean());
        assertFalse(ConfigFile.readPreferences(path).has("carpetRefill"));
    }
}
