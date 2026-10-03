package dev.magicshulkerboxes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ConfigMigrationTest {
    @TempDir Path directory;

    @Test void versionOneSettingsRenamePickupKeyAndPreserveEveryChoice() throws IOException {
        var serverPath = directory.resolve("server.json");
        var originalServer = "{\"configVersion\":1,\"enabled\":true,\"allowPlayerSettings\":true,\"includeOffhand\":true}";
        Files.writeString(serverPath, originalServer);
        var server = ConfigFile.load(serverPath);
        assertTrue(server.pickupStorageEnabled);
        assertTrue(server.allowPlayerSettings);
        assertTrue(server.includeOffhand);
        var serverText = Files.readString(serverPath);
        assertTrue(serverText.contains("\"configVersion\": 2"));
        assertTrue(serverText.contains("\"pickupStorageEnabled\": true"));
        assertFalse(serverText.contains("\"enabled\""));
        assertEquals(originalServer, Files.readString(directory.resolve("server.json.pre-0.3.2.bak")));

        var playerPath = directory.resolve("player.json");
        var originalPlayer = "{\"configVersion\":1,\"enabled\":false,\"schematicRefill\":true,\"makeSpaceMode\":\"DISABLED\"}";
        Files.writeString(playerPath, originalPlayer);
        var preferences = ConfigFile.readPreferences(playerPath);
        assertFalse(preferences.get("pickupStorageEnabled").getAsBoolean());
        assertTrue(preferences.get("schematicRefill").getAsBoolean());
        assertEquals("DISABLED", preferences.get("makeSpaceMode").getAsString());
        assertEquals(originalPlayer, Files.readString(directory.resolve("player.json.pre-0.3.2.bak")));
        assertEquals(preferences, ConfigFile.readPreferences(playerPath), "Migration runs once");
    }

    @Test void versionOneMigrationPreservesInvalidOrConflictingFiles() throws IOException {
        var path = directory.resolve("client.json");
        var invalid = "{\"configVersion\":1,\"enabled\":true,\"pickupStorageEnabled\":false}";
        Files.writeString(path, invalid);
        assertThrows(IOException.class, () -> ConfigFile.readPreferences(path));
        assertEquals(invalid, Files.readString(path));
        assertFalse(Files.exists(directory.resolve("client.json.pre-0.3.2.bak")));

        var valid = "{\"configVersion\":1,\"enabled\":true}";
        var backup = directory.resolve("client.json.pre-0.3.2.bak");
        Files.writeString(path, valid);
        Files.writeString(backup, "unrelated backup");
        assertThrows(IOException.class, () -> ConfigFile.readPreferences(path));
        assertEquals(valid, Files.readString(path));
        assertEquals("unrelated backup", Files.readString(backup));
    }

    @Test void startupMigratesVersionOneOfflinePlayerWithoutDiscardingChoices() throws IOException {
        var player = java.util.UUID.randomUUID();
        var path = directory.resolve(player + ".json");
        var original = "{\"configVersion\":1,\"enabled\":true,\"schematicRefill\":false}";
        Files.writeString(path, original);
        var store = new PlayerSettingsStore(directory);
        store.migrateExisting();
        var choices = store.read(player);
        assertTrue(choices.get("pickupStorageEnabled").getAsBoolean());
        assertFalse(choices.get("schematicRefill").getAsBoolean());
        assertEquals(original, Files.readString(directory.resolve(player + ".json.pre-0.3.2.bak")));
    }

    @Test void legacyServerSettingsAreBackedUpAndResetWithPickupOff() throws IOException {
        var path = directory.resolve("server.json");
        var old = "{\"enabled\":true,\"allowPlayerSettings\":true,\"includeOffhand\":true}";
        Files.writeString(path, old);
        var settings = ConfigFile.load(path);
        assertFalse(settings.pickupStorageEnabled, "Pickup is off after reset");
        assertFalse(settings.allowPlayerSettings);
        assertFalse(settings.includeOffhand);
        assertEquals(old, Files.readString(directory.resolve("server.json.pre-0.3.1.bak")));
        assertTrue(Files.readString(path).contains("\"configVersion\": 2"));
    }

    @Test void legacyPlayerAndClientOverridesResetToInheritanceExactlyOnce() throws IOException {
        var path = directory.resolve("player.json");
        var old = "{\"enabled\":false,\"schematicRefill\":true,\"makeSpaceMode\":\"DISABLED\"}";
        Files.writeString(path, old);
        assertTrue(ConfigFile.readPreferences(path).isEmpty());
        assertEquals(old, Files.readString(directory.resolve("player.json.pre-0.3.1.bak")));
        Files.writeString(path, "{\"configVersion\":2,\"pickupStorageEnabled\":true,\"useEmptyBoxes\":false}");
        var current = ConfigFile.readPreferences(path);
        assertTrue(current.get("pickupStorageEnabled").getAsBoolean(), "New choices survive future loads");
        assertFalse(current.get("useEmptyBoxes").getAsBoolean());
        assertFalse(current.has("configVersion"), "Metadata is not a gameplay option");
        assertEquals(old, Files.readString(directory.resolve("player.json.pre-0.3.1.bak")));
    }

    @Test void conflictingBackupPreventsResetAndPreservesBothFiles() throws IOException {
        var path = directory.resolve("player.json");
        var backup = directory.resolve("player.json.pre-0.3.1.bak");
        Files.writeString(path, "{\"enabled\":true}");
        Files.writeString(backup, "earlier backup");
        assertThrows(IOException.class, () -> ConfigFile.readPreferences(path));
        assertEquals("{\"enabled\":true}", Files.readString(path));
        assertEquals("earlier backup", Files.readString(backup));
    }

    @Test void futureOrMalformedVersionedSettingsAreNeverReset() throws IOException {
        var path = directory.resolve("player.json");
        for (var json : new String[]{"{\"configVersion\":3,\"pickupStorageEnabled\":true}",
                "{\"configVersion\":\"2\"}", "{\"configVersion\":1,\"enabled\":\"true\"}",
                "{\"configVersion\":2,\"enabled\":true}", "{"}) {
            Files.writeString(path, json);
            assertThrows(IOException.class, () -> ConfigFile.readPreferences(path));
            assertEquals(json, Files.readString(path));
            assertFalse(Files.exists(directory.resolve("player.json.pre-0.3.1.bak")));
            assertFalse(Files.exists(directory.resolve("player.json.pre-0.3.2.bak")));
        }
    }

    @Test void unavailableBackupDestinationNeverReplacesTheOriginal() throws IOException {
        var path = directory.resolve("client.json");
        var old = "{\"enabled\":true}";
        Files.writeString(path, old);
        Files.createDirectory(directory.resolve("client.json.pre-0.3.1.bak"));
        assertThrows(IOException.class, () -> ConfigFile.readPreferences(path));
        assertEquals(old, Files.readString(path));
    }

    @Test void freshSettingsDisablePickupAndSeparateMetadataFromOptions() throws IOException {
        assertFalse(new StorageConfig().pickupStorageEnabled);
        assertFalse(ConfigFile.load(directory.resolve("new-server.json")).pickupStorageEnabled);
        assertTrue(ConfigFile.readPreferences(directory.resolve("new-client.json")).isEmpty());
        assertFalse(ConfigFile.optionNames().contains("configVersion"));
        assertTrue(Files.readString(directory.resolve("new-client.json")).contains("\"configVersion\": 2"));
    }

    @Test void oldSettingsChannelsCannotUploadPreResetPreferences() {
        assertEquals("preferences_v5", SettingsNetwork.Preferences.ID.id().getPath());
        assertEquals("policy_v5", SettingsNetwork.Policy.ID.id().getPath());
        assertEquals("editor_save_v5", EditorNetwork.Save.ID.id().getPath());
        assertEquals("editor_query_v5", EditorNetwork.Query.ID.id().getPath());
        assertEquals("editor_result_v5", EditorNetwork.Result.ID.id().getPath());
        assertEquals("editor_state_v5", EditorNetwork.State.ID.id().getPath());
    }

    @Test void versionedSavesSurviveRestartAndResetDoesNotFillInPersonalOverrides() throws IOException {
        var serverPath = directory.resolve("server.json");
        var server = new ServerConfig(); server.pickupStorageEnabled = true; server.includeOffhand = true;
        ConfigFile.writeServer(serverPath, server);
        assertTrue(ConfigFile.load(serverPath).pickupStorageEnabled);
        assertTrue(ConfigFile.load(serverPath).includeOffhand);
        var clientPath = directory.resolve("client.json");
        ConfigFile.writePreferences(clientPath, ConfigFile.parsePreferences("{\"pickupStorageEnabled\":true}"));
        assertEquals(1, ConfigFile.readPreferences(clientPath).size());
        assertTrue(ConfigFile.readPreferences(clientPath).get("pickupStorageEnabled").getAsBoolean());
        assertFalse(Files.exists(directory.resolve("client.json.pre-0.3.1.bak")));
        ConfigFile.writePreferences(clientPath, ConfigFile.parsePreferences("{}"));
        assertTrue(ConfigFile.readPreferences(clientPath).isEmpty(), "Reset means inherit, not copy defaults");
    }

    @Test void migrationResumesAfterBackupWithoutOverwritingIt() throws IOException {
        var path = directory.resolve("client.json");
        var backup = directory.resolve("client.json.pre-0.3.1.bak");
        var old = "{\"enabled\":true}";
        Files.writeString(path, old); Files.writeString(backup, old);
        assertTrue(ConfigFile.readPreferences(path).isEmpty());
        assertEquals(old, Files.readString(backup));
        assertTrue(ConfigFile.readPreferences(path).isEmpty());
    }

    @Test void startupMigratesOfflinePlayersAndIsolatesAnUnreadablePlayer() throws IOException {
        var good = java.util.UUID.randomUUID(); var bad = java.util.UUID.randomUUID();
        Files.writeString(directory.resolve(good + ".json"), "{\"enabled\":true}");
        Files.writeString(directory.resolve(bad + ".json"), "{");
        Files.writeString(directory.resolve("unrelated.json"), "leave alone");
        var store = new PlayerSettingsStore(directory);
        store.migrateExisting();
        assertTrue(store.read(good).isEmpty());
        assertThrows(IOException.class, () -> store.read(bad));
        assertTrue(Files.exists(directory.resolve(good + ".json.pre-0.3.1.bak")));
        assertEquals("leave alone", Files.readString(directory.resolve("unrelated.json")));
        store.save(good, ConfigFile.parsePreferences("{\"pickupStorageEnabled\":true}"));
        var restarted = new PlayerSettingsStore(directory); restarted.migrateExisting();
        assertTrue(restarted.read(good).get("pickupStorageEnabled").getAsBoolean());
    }

    @Test void savingBeforeFirstReadStillBacksUpLegacyContents() throws IOException {
        var path = directory.resolve("client.json");
        var old = "{\"useHotbarForSpace\":true}";
        Files.writeString(path, old);
        ConfigFile.writePreferences(path, ConfigFile.parsePreferences("{\"pickupStorageEnabled\":true}"));
        assertEquals(old, Files.readString(directory.resolve("client.json.pre-0.3.1.bak")));
        var values = ConfigFile.readPreferences(path);
        assertEquals(1, values.size());
        assertTrue(values.get("pickupStorageEnabled").getAsBoolean());
    }
}
