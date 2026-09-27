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
    void clientAndServerOverridesKeepFeatureLimitsIndependent() throws IOException {
        for (boolean pickup : new boolean[]{false, true}) {
            for (boolean refill : new boolean[]{false, true}) {
                var defaults = ConfigFile.parseServer("{\"enabled\":" + pickup + ",\"schematicRefill\":" + refill + "}");
                for (boolean personalPickup : new boolean[]{false, true}) {
                    for (boolean personalRefill : new boolean[]{false, true}) {
                        var personal = ConfigFile.parsePreferences("{\"enabled\":" + personalPickup
                                + ",\"schematicRefill\":" + personalRefill + "}");
                        var effective = ConfigFile.apply(defaults, personal);
                        assertEquals(pickup && personalPickup, effective.enabled);
                        assertEquals(refill && personalRefill, effective.schematicRefill);
                        assertEquals(pickup, defaults.enabled, "Overrides never mutate server defaults");
                        assertEquals(refill, defaults.schematicRefill);
                    }
                }
                var inherited = ConfigFile.apply(defaults, ConfigFile.parsePreferences("{}"));
                assertEquals(pickup, inherited.enabled);
                assertEquals(refill, inherited.schematicRefill);
            }
        }
    }

    @Test
    void firstLoadWritesDefaults() throws IOException {
        var path = directory.resolve("config/magic_shulker_boxes.json");
        var config = ConfigFile.load(path);
        assertTrue(config.enabled);
        assertFalse(config.allowPlayerSettings);
        assertTrue(config.onlyWhenInventoryFull);
        assertFalse(config.allowOtherSingleTypeBoxes);
        assertEquals(StorageConfig.MakeSpaceMode.MOVE_TO_BOX, config.makeSpaceMode);
        assertTrue(Files.readString(path).contains("allowOtherSingleTypeBoxes"));
    }

    @Test
    void acceptsPartialConfigWithoutOverwritingUsersFile() throws IOException {
        var path = directory.resolve("config.json");
        var json = "{\"allowOtherSingleTypeBoxes\":true,\"useEmptyBoxes\":false}";
        Files.writeString(path, json);
        var config = ConfigFile.load(path);
        assertTrue(config.allowOtherSingleTypeBoxes);
        assertFalse(config.useEmptyBoxes);
        assertTrue(config.useMixedBoxes);
        assertEquals(json, Files.readString(path));
    }

    @Test
    void acceptsAllSpaceModesAndRejectsUnknownOrMistypedValues() throws IOException {
        var path = directory.resolve("config.json");
        for (var mode : StorageConfig.MakeSpaceMode.values()) {
            Files.writeString(path, "{\"makeSpaceMode\":\"" + mode + "\",\"useHotbarForSpace\":true}");
            var config = ConfigFile.load(path);
            assertEquals(mode, config.makeSpaceMode);
            assertTrue(config.useHotbarForSpace);
        }
        for (var value : new String[] {"\"unknown\"", "true", "null", "1"}) {
            Files.writeString(path, "{\"makeSpaceMode\":" + value + "}");
            assertThrows(IOException.class, () -> ConfigFile.load(path));
        }
    }

    @Test
    void rejectsBrokenOrMistypedConfigWithoutOverwritingIt() throws IOException {
        var path = directory.resolve("config.json");
        for (var json : new String[] {"{", "null", "[]", "{\"enabled\":\"false\"}",
                "{\"enabled\":null}", "{\"useEmptyBox\":true}"}) {
            Files.writeString(path, json);
            assertThrows(IOException.class, () -> ConfigFile.load(path), json);
            assertEquals(json, Files.readString(path));
        }
    }
}
