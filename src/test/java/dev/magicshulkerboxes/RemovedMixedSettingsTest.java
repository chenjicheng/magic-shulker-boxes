package dev.magicshulkerboxes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RemovedMixedSettingsTest {
    private static final List<String> REMOVED = List.of("useMixedBoxes", "allowOtherSingleTypeBoxes", "allowMixedItemsWhenMakingSpace");
    @TempDir Path directory;

    @Test void retiredOptionsAreUnavailableAtLiveInputBoundaries() {
        for (var key : REMOVED) {
            assertFalse(ConfigFile.optionNames().contains(key));
            assertThrows(IOException.class, () -> ConfigFile.parsePreferences("{\"" + key + "\":true}"));
        }
    }

    @Test void oldSettingsAndPermissionsMigrateWithoutLosingOtherChoices() throws IOException {
        var path = directory.resolve("server.json");
        var original = "{\"configVersion\":2,\"useMixedBoxes\":true,\"allowOtherSingleTypeBoxes\":false,"
                + "\"allowMixedItemsWhenMakingSpace\":true,\"craftRefill\":false,"
                + "\"playerEditableSettings\":[\"useMixedBoxes\",\"craftRefill\"]}";
        Files.writeString(path, original);
        var config = ConfigFile.load(path);
        assertFalse(config.craftRefill);
        assertEquals(List.of("craftRefill"), config.playerEditableSettings);
        for (var key : REMOVED) assertFalse(Files.readString(path).contains(key));
        assertEquals(original, Files.readString(directory.resolve("server.json.pre-single-type.bak")));
    }

    @Test void oldInventoryPriorityMigratesItsInverseAndPermission() throws IOException {
        for (boolean old : new boolean[] {false, true}) {
            var path = directory.resolve("server-" + old + ".json");
            var original = "{\"configVersion\":2,\"onlyWhenInventoryFull\":" + old
                    + ",\"craftRefill\":false,\"playerEditableSettings\":[\"onlyWhenInventoryFull\"]}";
            Files.writeString(path, original);
            var config = ConfigFile.load(path);
            assertEquals(!old, config.preferEmptyBoxesOverInventory);
            assertFalse(config.craftRefill);
            assertEquals(List.of("preferEmptyBoxesOverInventory"), config.playerEditableSettings);
            assertEquals(original, Files.readString(directory.resolve(path.getFileName() + ".pre-single-type.bak")));
            assertFalse(Files.readString(path).contains("onlyWhenInventoryFull"));
        }
        assertThrows(IOException.class, () -> ConfigFile.parsePreferences("{\"onlyWhenInventoryFull\":true}"));
    }

    @Test void oldPreferencesAndPermissionOnlyFilesRetainSparseInheritance() throws IOException {
        var path = directory.resolve("client.json");
        Files.writeString(path, "{\"configVersion\":2,\"useMixedBoxes\":false,\"onlyWhenInventoryFull\":true,\"craftRefill\":false}");
        var choices = ConfigFile.readPreferences(path);
        assertEquals(2, choices.size());
        assertFalse(choices.get("craftRefill").getAsBoolean());
        assertFalse(choices.get("preferEmptyBoxesOverInventory").getAsBoolean());
        var permissionPath = directory.resolve("permissions.json");
        Files.writeString(permissionPath, "{\"configVersion\":2,\"playerEditableSettings\":[\"onlyWhenInventoryFull\"]}");
        assertEquals(List.of("preferEmptyBoxesOverInventory"), ConfigFile.load(permissionPath).playerEditableSettings);
        assertTrue(Files.exists(directory.resolve("permissions.json.pre-single-type.bak")));
    }

    @Test void invalidSettingsAndBackupConflictsNeverOverwriteUserData() throws IOException {
        var path = directory.resolve("invalid.json");
        for (var original : List.of("{\"configVersion\":2,\"useMixedBoxes\":\"true\"}",
                "{\"configVersion\":2,\"onlyWhenInventoryFull\":true,\"preferEmptyBoxesOverInventory\":false}",
                "{\"configVersion\":2,\"useMixedBoxes\":true,\"craftRefill\":\"false\"}")) {
            Files.writeString(path, original);
            assertThrows(IOException.class, () -> ConfigFile.load(path));
            assertEquals(original, Files.readString(path));
            assertFalse(Files.exists(directory.resolve("invalid.json.pre-single-type.bak")));
        }
        var original = "{\"configVersion\":2,\"useMixedBoxes\":true}";
        Files.writeString(path, original);
        var backup = directory.resolve("invalid.json.pre-single-type.bak");
        Files.writeString(backup, "unrelated backup");
        assertThrows(IOException.class, () -> ConfigFile.load(path));
        assertEquals(original, Files.readString(path));
        assertEquals("unrelated backup", Files.readString(backup));
    }
}
