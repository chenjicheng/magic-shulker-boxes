package dev.magicshulkerboxes;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PlayerSettingsStoreTest {
    @TempDir Path directory;

    @Test
    void serverPolicyAndGlobalOffAlwaysWin() throws IOException {
        var store = new PlayerSettingsStore(directory);
        var player = UUID.randomUUID();
        store.save(player, ConfigFile.parsePreferences("{\"enabled\":true,\"useEmptyBoxes\":false}"));
        var server = new ServerConfig();
        assertTrue(store.resolve(player, server).useEmptyBoxes);
        server.allowPlayerSettings = true;
        assertFalse(store.resolve(player, server).useEmptyBoxes);
        server.enabled = false;
        assertFalse(store.resolve(player, server).enabled);
    }

    @Test
    void isolatesPlayersPersistsAndInheritsUnspecifiedDefaults() throws IOException {
        var first = UUID.randomUUID();
        var second = UUID.randomUUID();
        var store = new PlayerSettingsStore(directory);
        var server = new ServerConfig();
        server.allowPlayerSettings = true;
        store.save(first, ConfigFile.parsePreferences("{\"makeSpaceMode\":\"DISABLED\"}"));
        assertEquals(StorageConfig.MakeSpaceMode.DISABLED, store.resolve(first, server).makeSpaceMode);
        assertEquals(StorageConfig.MakeSpaceMode.MOVE_TO_BOX, store.resolve(second, server).makeSpaceMode);
        server.useMixedBoxes = false;
        assertFalse(store.resolve(first, server).useMixedBoxes);
        store = new PlayerSettingsStore(directory);
        assertEquals(StorageConfig.MakeSpaceMode.DISABLED, store.resolve(first, server).makeSpaceMode);
        var returned = store.read(first);
        returned.addProperty("useEmptyBoxes", false);
        assertTrue(store.resolve(first, server).useEmptyBoxes, "Returned snapshots cannot modify stored preferences");
        store.save(first, ConfigFile.parsePreferences("{}"));
        assertEquals(StorageConfig.MakeSpaceMode.MOVE_TO_BOX, new PlayerSettingsStore(directory).resolve(first, server).makeSpaceMode);
    }

    @Test
    void rejectsServerPolicyInvalidTypesAndOversizedPayloads() throws IOException {
        for (var json : new String[]{"{\"allowPlayerSettings\":true}", "{\"enabled\":\"false\"}",
                "{\"makeSpaceMode\":\"BAD\"}", "{\"player\":\"somebody-else\"}", "[]", "null", "{"}) {
            assertThrows(IOException.class, () -> ConfigFile.parsePreferences(json), json);
        }
        assertThrows(IOException.class, () -> ConfigFile.parsePreferences(" ".repeat(4097) + "{}"));
        assertFalse(ConfigFile.parsePreferences("{\"enabled\":false}").get("enabled").getAsBoolean());
    }
}
