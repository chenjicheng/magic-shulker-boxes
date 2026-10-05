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
    void personalPickupCanOptInOverServerDefaultWhenPolicyAllows() throws IOException {
        var store = new PlayerSettingsStore(directory);
        var player = UUID.randomUUID();
        var server = new ServerConfig();
        assertFalse(server.pickupStorageEnabled);
        store.save(player, ConfigFile.parsePreferences("{\"pickupStorageEnabled\":true}"));

        assertFalse(store.resolve(player, server).pickupStorageEnabled, "Policy off enforces the server default");
        server.allowPlayerSettings = true;
        assertTrue(store.resolve(player, server).pickupStorageEnabled, "Allowed personal setting enables pickup");

        store.save(player, ConfigFile.parsePreferences("{\"pickupStorageEnabled\":false}"));
        assertFalse(store.resolve(player, server).pickupStorageEnabled, "Personal off remains independent");
        store.save(player, ConfigFile.parsePreferences("{}"));
        assertFalse(store.resolve(player, server).pickupStorageEnabled, "Clearing the override inherits the server default");
    }

    @Test
    void personalRefillCanOptInOverServerDefaultWhenPolicyAllows() throws IOException {
        var store = new PlayerSettingsStore(directory);
        var player = UUID.randomUUID();
        var server = new ServerConfig();
        server.schematicRefill = false;
        store.save(player, ConfigFile.parsePreferences("{\"schematicRefill\":true}"));

        assertFalse(store.resolve(player, server).schematicRefill, "Policy off enforces the server default");
        server.allowPlayerSettings = true;
        assertTrue(store.resolve(player, server).schematicRefill, "Allowed personal setting enables refilling");

        store.save(player, ConfigFile.parsePreferences("{\"schematicRefill\":false}"));
        assertFalse(store.resolve(player, server).schematicRefill, "Personal off remains independent");
        store.save(player, ConfigFile.parsePreferences("{}"));
        assertFalse(store.resolve(player, server).schematicRefill, "Clearing the override inherits the server default");
    }

    @Test
    void serverPolicyControlsPersonalOverrides() throws IOException {
        var store = new PlayerSettingsStore(directory);
        var player = UUID.randomUUID();
        store.save(player, ConfigFile.parsePreferences("{\"pickupStorageEnabled\":true,\"useEmptyBoxes\":false}"));
        var server = new ServerConfig();
        assertTrue(store.resolve(player, server).useEmptyBoxes);
        server.allowPlayerSettings = true;
        assertFalse(store.resolve(player, server).useEmptyBoxes);
        server.pickupStorageEnabled = false;
        assertTrue(store.resolve(player, server).pickupStorageEnabled);
    }

    @Test
    void disablingPickupStillAppliesPersonalRefillAndBehaviorPreferences() throws IOException {
        var store = new PlayerSettingsStore(directory);
        var player = UUID.randomUUID();
        var server = new ServerConfig();
        server.pickupStorageEnabled = false;
        server.allowPlayerSettings = true;
        store.save(player, ConfigFile.parsePreferences("{\"schematicRefill\":false,\"refillFullStack\":false}"));
        var effective = store.resolve(player, server);
        assertFalse(effective.pickupStorageEnabled);
        assertFalse(effective.schematicRefill, "Personal refill off still applies when pickup is off");
        assertFalse(effective.refillFullStack, "Pickup does not gate unrelated personal settings");
        store.save(player, ConfigFile.parsePreferences("{\"pickupStorageEnabled\":true,\"schematicRefill\":true}"));
        effective = store.resolve(player, server);
        assertTrue(effective.pickupStorageEnabled, "Allowed personal pickup on overrides the server default");
        assertTrue(effective.schematicRefill, "Server pickup off does not disable refill");
        server.allowPlayerSettings = false;
        assertTrue(store.resolve(player, server).refillFullStack, "Policy revocation restores server defaults");
    }

    @Test
    void personalRefillCanOverrideServerDefaultWithoutChangingPickup() throws IOException {
        var store = new PlayerSettingsStore(directory);
        var player = UUID.randomUUID();
        var server = new ServerConfig();
        server.pickupStorageEnabled = true;
        server.schematicRefill = false;
        server.allowPlayerSettings = true;
        store.save(player, ConfigFile.parsePreferences("{\"pickupStorageEnabled\":true,\"schematicRefill\":true}"));
        assertTrue(store.resolve(player, server).pickupStorageEnabled);
        assertTrue(store.resolve(player, server).schematicRefill);
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
        server.useMatchingBoxes = false;
        assertFalse(store.resolve(first, server).useMatchingBoxes);
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
        for (var json : new String[]{"{\"allowPlayerSettings\":true}", "{\"pickupStorageEnabled\":\"false\"}",
                "{\"makeSpaceMode\":\"BAD\"}", "{\"player\":\"somebody-else\"}", "[]", "null", "{"}) {
            assertThrows(IOException.class, () -> ConfigFile.parsePreferences(json), json);
        }
        assertThrows(IOException.class, () -> ConfigFile.parsePreferences(" ".repeat(4097) + "{}"));
        assertFalse(ConfigFile.parsePreferences("{\"pickupStorageEnabled\":false}").get("pickupStorageEnabled").getAsBoolean());
    }
}
