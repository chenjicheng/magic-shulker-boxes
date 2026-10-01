package dev.magicshulkerboxes;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SettingPermissionsTest {
    @TempDir Path directory;

    @Test
    void existingOverridesFollowFieldPermissionsAndRuntimeChanges() throws IOException {
        var store = new PlayerSettingsStore(directory);
        var player = UUID.randomUUID();
        store.save(
                player,
                ConfigFile.parsePreferences("{\"pickupStorageEnabled\":true,\"ipnRefill\":false}"));
        var server =
                ConfigFile.parseServer(
                        "{\"allowPlayerSettings\":true,\"playerEditableSettings\":[\"ipnRefill\"]}");
        assertFalse(
                store.resolve(player, server).pickupStorageEnabled,
                "Locked field uses server value even for an existing file");
        assertFalse(store.resolve(player, server).ipnRefill);
        server =
                ConfigFile.parseServer(
                        "{\"allowPlayerSettings\":true,\"playerEditableSettings\":[\"pickupStorageEnabled\"]}");
        assertTrue(store.resolve(player, server).pickupStorageEnabled);
        assertTrue(
                store.resolve(player, server).ipnRefill,
                "Revoked field immediately inherits server value");
        assertEquals(2, store.read(player).size(), "Policy changes do not destroy stored choices");
    }

    @Test
    void validatesOnlyKnownUniqueOptionNamesAndKeepsPolicyOutOfPersonalData() {
        for (String value :
                new String[] {
                    "true",
                    "null",
                    "[true]",
                    "[\"allowPlayerSettings\"]",
                    "[\"ipnRefill\",\"ipnRefill\"]",
                    "[\"unknown\"]"
                }) {
            assertThrows(
                    IOException.class,
                    () -> ConfigFile.parseServer("{\"playerEditableSettings\":" + value + "}"));
        }
        assertThrows(
                IOException.class,
                () -> ConfigFile.parsePreferences("{\"playerEditableSettings\":[]}"));
    }

    @Test
    void savesRejectLockedFieldsAndResetOnlyEditableChoices() throws IOException {
        var store = new PlayerSettingsStore(directory);
        var player = UUID.randomUUID();
        var server =
                ConfigFile.parseServer(
                        "{\"allowPlayerSettings\":true,\"playerEditableSettings\":[\"ipnRefill\"]}");
        store.save(
                player,
                ConfigFile.parsePreferences("{\"pickupStorageEnabled\":true,\"ipnRefill\":false}"));
        assertThrows(
                IOException.class,
                () ->
                        store.saveAllowed(
                                player,
                                ConfigFile.parsePreferences("{\"pickupStorageEnabled\":false}"),
                                server));
        assertTrue(store.read(player).get("pickupStorageEnabled").getAsBoolean());
        store.saveAllowed(player, ConfigFile.parsePreferences("{}"), server);
        assertEquals(1, store.read(player).size());
        assertTrue(store.resolve(player, server).ipnRefill);
        server.allowPlayerSettings = false;
        assertThrows(
                IOException.class,
                () -> store.saveAllowed(player, ConfigFile.parsePreferences("{}"), server));
    }
}
