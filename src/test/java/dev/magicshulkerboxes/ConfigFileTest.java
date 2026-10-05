package dev.magicshulkerboxes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class ConfigFileTest {
    @TempDir Path directory;

    @Test
    void spaceNoticesDefaultOnAndRemainIndependentOfOtherRefillNotices() throws IOException {
        var config = ConfigFile.parseServer("{\"refillFailureMessages\":false}");
        assertTrue(config.spaceFailureMessages);
        var preferences = ConfigFile.parsePreferences("{\"spaceFailureMessages\":false}");
        var effective = ConfigFile.apply(config, preferences);
        assertFalse(effective.spaceFailureMessages);
        assertFalse(effective.refillFailureMessages);
        assertTrue(config.spaceFailureMessages, "Personal choice does not change server defaults");
        var path = directory.resolve("old.json");
        var original = "{\"configVersion\":2,\"refillFailureMessages\":false}";
        Files.writeString(path, original);
        assertTrue(ConfigFile.load(path).spaceFailureMessages);
        assertEquals(original, Files.readString(path), "The additive setting does not rewrite old files");
    }

    @Test
    void craftingRefillDefaultsOnAndKeepsExplicitOptOuts() throws IOException {
        assertTrue(new StorageConfig().craftRefill);
        assertTrue(ConfigFile.parseServer("{}").craftRefill);
        assertTrue(ConfigFile.load(directory.resolve("new.json")).craftRefill);
        var path = directory.resolve("existing.json");
        var existing = "{\"configVersion\":2,\"craftRefill\":false}";
        Files.writeString(path, existing);
        assertFalse(ConfigFile.load(path).craftRefill);
        assertEquals(existing, Files.readString(path), "Explicit existing choice is preserved");
        assertFalse(ConfigFile.apply(new ServerConfig(), ConfigFile.parsePreferences("{\"craftRefill\":false}")).craftRefill);
    }

    @Test
    void personalFeatureChoicesOverrideDefaultsIndependently() throws IOException {
        for (boolean pickup : new boolean[]{false, true}) {
            for (boolean refill : new boolean[]{false, true}) {
                var defaults = ConfigFile.parseServer("{\"pickupStorageEnabled\":" + pickup + ",\"schematicRefill\":" + refill + "}");
                for (boolean personalPickup : new boolean[]{false, true}) {
                    for (boolean personalRefill : new boolean[]{false, true}) {
                        var personal = ConfigFile.parsePreferences("{\"pickupStorageEnabled\":" + personalPickup
                                + ",\"schematicRefill\":" + personalRefill + "}");
                        var effective = ConfigFile.apply(defaults, personal);
                        assertEquals(personalPickup, effective.pickupStorageEnabled);
                        assertEquals(personalRefill, effective.schematicRefill);
                        assertEquals(pickup, defaults.pickupStorageEnabled, "Overrides never mutate server defaults");
                        assertEquals(refill, defaults.schematicRefill);
                    }
                }
                var inherited = ConfigFile.apply(defaults, ConfigFile.parsePreferences("{}"));
                assertEquals(pickup, inherited.pickupStorageEnabled);
                assertEquals(refill, inherited.schematicRefill);
            }
        }
    }

    @Test
    void firstLoadWritesDefaults() throws IOException {
        var path = directory.resolve("config/magic_shulker_boxes.json");
        var config = ConfigFile.load(path);
        assertFalse(config.pickupStorageEnabled);
        assertFalse(config.allowPlayerSettings);
        assertFalse(config.preferEmptyBoxesOverInventory);
        assertTrue(config.useMatchingBoxes);
        assertEquals(StorageConfig.MakeSpaceMode.MOVE_TO_BOX, config.makeSpaceMode);
        assertTrue(Files.readString(path).contains("useMatchingBoxes"));
    }

    @Test
    void acceptsPartialConfigWithoutOverwritingUsersFile() throws IOException {
        var path = directory.resolve("config.json");
        var json = "{\"configVersion\":2,\"useMatchingBoxes\":true,\"useEmptyBoxes\":false}";
        Files.writeString(path, json);
        var config = ConfigFile.load(path);
        assertTrue(config.useMatchingBoxes);
        assertFalse(config.useEmptyBoxes);
        assertTrue(config.splitStackedBoxes);
        assertEquals(json, Files.readString(path));
    }

    @Test
    void acceptsAllSpaceModesAndRejectsUnknownOrMistypedValues() throws IOException {
        var path = directory.resolve("config.json");
        for (var mode : StorageConfig.MakeSpaceMode.values()) {
            Files.writeString(path, "{\"configVersion\":2,\"makeSpaceMode\":\"" + mode + "\",\"useHotbarForSpace\":true}");
            var config = ConfigFile.load(path);
            assertEquals(mode, config.makeSpaceMode);
            assertTrue(config.useHotbarForSpace);
        }
        for (var value : new String[] {"\"unknown\"", "true", "null", "1"}) {
            Files.writeString(path, "{\"configVersion\":2,\"makeSpaceMode\":" + value + "}");
            assertThrows(IOException.class, () -> ConfigFile.load(path));
        }
    }

    @Test
    void rejectsBrokenOrMistypedConfigWithoutOverwritingIt() throws IOException {
        var path = directory.resolve("config.json");
        for (var json : new String[] {"{", "null", "[]", "{\"configVersion\":2,\"pickupStorageEnabled\":\"false\"}",
                "{\"configVersion\":2,\"pickupStorageEnabled\":null}", "{\"configVersion\":2,\"useEmptyBox\":true}"}) {
            Files.writeString(path, json);
            assertThrows(IOException.class, () -> ConfigFile.load(path), json);
            assertEquals(json, Files.readString(path));
        }
    }
}
