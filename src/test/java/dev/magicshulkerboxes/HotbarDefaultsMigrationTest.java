package dev.magicshulkerboxes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class HotbarDefaultsMigrationTest {
    @TempDir Path directory;

    @Test void newConfigurationsProtectEveryHotbarSlot() throws IOException {
        assertAllSlots(true, new StorageConfig());
        assertAllSlots(true, ConfigFile.parseServer("{}"));
        var path = directory.resolve("fresh-server.json");
        assertAllSlots(true, ConfigFile.load(path));
        assertTrue(Files.readString(path).contains("\"configVersion\": 3"));
        assertFalse(new StorageConfig().useHotbarForSpace, "Protection does not enable relocation");
    }

    @Test void versionTwoServerConfigEnablesEveryHotbarProtectionOnce() throws IOException {
        var path = directory.resolve("server.json");
        var original = serverDefaults();
        Files.writeString(path, original);
        var config = ConfigFile.load(path);
        assertAllSlots(true, config);
        assertFalse(config.craftRefill);
        var migrated = Files.readString(path);
        assertTrue(migrated.contains("\"configVersion\": 3"));
        for (int slot = 1; slot <= 9; slot++)
            assertTrue(migrated.contains("\"protectHotbarSlot" + slot + "\": true"), "slot " + slot);
        assertEquals(original, Files.readString(directory.resolve("server.json.pre-0.13.0.bak")));
        ConfigFile.load(path);
        assertEquals(migrated, Files.readString(path), "Migration runs once");
    }

    @Test void versionTwoPersonalConfigFlipsExplicitFalseValuesOnly() throws IOException {
        var path = directory.resolve("player.json");
        var original = "{\"configVersion\":2,\"protectHotbarSlot1\":false,\"protectHotbarSlot3\":false,\"craftRefill\":false}";
        Files.writeString(path, original);
        var values = ConfigFile.readPreferences(path);
        assertTrue(values.get("protectHotbarSlot1").getAsBoolean());
        assertTrue(values.get("protectHotbarSlot3").getAsBoolean());
        assertFalse(values.get("craftRefill").getAsBoolean());
        assertFalse(values.has("protectHotbarSlot2"), "Absent overrides keep inheriting");
        assertTrue(Files.readString(path).contains("\"configVersion\": 3"));
        assertEquals(original, Files.readString(directory.resolve("player.json.pre-0.13.0.bak")));
        assertEquals(values, ConfigFile.readPreferences(path));
    }

    @Test void versionThreeExplicitOptOutsAreNotMigratedAgain() throws IOException {
        var path = directory.resolve("server.json");
        var original = "{\"configVersion\":3" + allSlots(false) + "}";
        Files.writeString(path, original);
        var config = ConfigFile.load(path);
        assertAllSlots(false, config);
        assertEquals(original, Files.readString(path));
    }

    @Test void versionOneServerConfigReceivesProtectionDefaults() throws IOException {
        var path = directory.resolve("legacy.json");
        var original = "{\"configVersion\":1,\"enabled\":true,\"allowPlayerSettings\":true}";
        Files.writeString(path, original);
        var config = ConfigFile.load(path);
        assertTrue(config.pickupStorageEnabled);
        assertAllSlots(true, config);
        assertTrue(Files.readString(path).contains("\"configVersion\": 3"));
        assertEquals(original, Files.readString(directory.resolve("legacy.json.pre-0.3.2.bak")));
    }

    @Test void invalidHotbarValueInVersionTwoKeepsTheFile() throws IOException {
        var path = directory.resolve("invalid.json");
        var original = "{\"configVersion\":2,\"protectHotbarSlot5\":\"false\"}";
        Files.writeString(path, original);
        assertThrows(IOException.class, () -> ConfigFile.load(path));
        assertEquals(original, Files.readString(path));
        assertFalse(Files.exists(directory.resolve("invalid.json.pre-0.13.0.bak")));
    }

    @Test void conflictingHotbarMigrationBackupKeepsBothFiles() throws IOException {
        var path = directory.resolve("conflict.json");
        var original = "{\"configVersion\":2,\"protectHotbarSlot5\":false}";
        Files.writeString(path, original);
        var backup = directory.resolve("conflict.json.pre-0.13.0.bak");
        Files.writeString(backup, "different backup");
        assertThrows(IOException.class, () -> ConfigFile.load(path));
        assertEquals(original, Files.readString(path));
        assertEquals("different backup", Files.readString(backup));
    }

    @Test void shippedExamplesLoadWithCurrentDefaultsAndSchema() throws IOException {
        var server = ConfigFile.load(Path.of("examples", "magic_shulker_boxes.json"));
        assertAllSlots(true, server);
        assertFalse(ConfigFile.options(server).has("carpetRefill"));
        var client = ConfigFile.readPreferences(Path.of("examples", "magic_shulker_boxes-client.json"));
        assertFalse(client.get("useHotbarForSpace").getAsBoolean());
        assertFalse(client.has("protectHotbarSlot1"), "The sparse client example keeps inheriting");
    }

    private static String serverDefaults() {
        return "{\"configVersion\":2" + allSlots(false) + ",\"craftRefill\":false}";
    }

    private static String allSlots(boolean value) {
        var json = new StringBuilder();
        for (int slot = 1; slot <= 9; slot++) json.append(",\"protectHotbarSlot").append(slot).append("\":").append(value);
        return json.toString();
    }

    private static void assertAllSlots(boolean expected, StorageConfig config) {
        for (int slot = 0; slot < 9; slot++)
            assertEquals(expected, HotbarProtection.protectedSlot(slot, config), "slot " + (slot + 1));
    }
}
