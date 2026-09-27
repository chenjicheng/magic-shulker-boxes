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
    void serverPolicyAndPickupDisableAlwaysWin() throws IOException {
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
    void disablingPickupStillAppliesPersonalRefillAndBehaviorPreferences() throws IOException {
        var store = new PlayerSettingsStore(directory);
        var player = UUID.randomUUID();
        var server = new ServerConfig();
        server.enabled = false;
        server.allowPlayerSettings = true;
        store.save(player, ConfigFile.parsePreferences("{\"schematicRefill\":false,\"refillFullStack\":false}"));
        var effective = store.resolve(player, server);
        assertFalse(effective.enabled);
        assertFalse(effective.schematicRefill, "Personal refill off still applies when pickup is off");
        assertFalse(effective.refillFullStack, "Pickup does not gate unrelated personal settings");
        store.save(player, ConfigFile.parsePreferences("{\"enabled\":true,\"schematicRefill\":true}"));
        effective = store.resolve(player, server);
        assertFalse(effective.enabled, "Personal pickup on cannot override server pickup off");
        assertTrue(effective.schematicRefill, "Server pickup off does not disable refill");
        server.allowPlayerSettings = false;
        assertTrue(store.resolve(player, server).refillFullStack, "Policy revocation restores server defaults");
    }

    @Test
    void serverRefillDisableCannotBeOverriddenButPickupRemainsAvailable() throws IOException {
        var store = new PlayerSettingsStore(directory);
        var player = UUID.randomUUID();
        var server = new ServerConfig();
        server.schematicRefill = false;
        server.allowPlayerSettings = true;
        store.save(player, ConfigFile.parsePreferences("{\"enabled\":true,\"schematicRefill\":true}"));
        assertTrue(store.resolve(player, server).enabled);
        assertFalse(store.resolve(player, server).schematicRefill);
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
