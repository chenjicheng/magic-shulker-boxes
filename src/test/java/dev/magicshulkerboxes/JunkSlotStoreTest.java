package dev.magicshulkerboxes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class JunkSlotStoreTest {
    @TempDir Path directory;

    @Test void choicesSurviveReopeningWithoutChangingOtherPlayers() throws IOException {
        var first = UUID.randomUUID(); var second = UUID.randomUUID();
        var store = new JunkSlotStore(directory);
        var saved = store.update(first, 0, (1L << 9) | (1L << 35));
        assertEquals((1L << 9) | (1L << 35), saved.mask());
        assertEquals(1, saved.revision());
        assertEquals(0, store.read(second).mask());
        assertEquals(saved.mask(), new JunkSlotStore(directory).read(first).mask());
    }

    @Test void staleOrInvalidUpdatesLeaveSavedChoicesUntouched() throws IOException {
        var player = UUID.randomUUID(); var store = new JunkSlotStore(directory);
        var first = store.update(player, 0, 1L << 10);
        assertThrows(JunkSlotStore.Changed.class, () -> store.update(player, 0, 1L << 11));
        assertThrows(IllegalArgumentException.class, () -> store.update(player, first.revision(), 1L << 36));
        assertEquals(1L << 10, store.read(player).mask());
        assertEquals(first, store.update(player, first.revision(), first.mask()), "An unchanged choice is idempotent");
    }

    @Test void futureAndMalformedFilesAreNotOverwritten() throws IOException {
        var player = UUID.randomUUID(); var path = directory.resolve(player + ".json");
        for (var source : new String[] {"{\"schemaVersion\":2,\"slots\":[9]}",
                "{\"schemaVersion\":1,\"slots\":[9,9]}", "{\"schemaVersion\":1,\"slots\":[36]}",
                "{\"schemaVersion\":1,\"slots\":[9.5]}", "broken"}) {
            Files.writeString(path, source);
            var store = new JunkSlotStore(directory);
            assertThrows(IOException.class, () -> store.read(player));
            assertThrows(IOException.class, () -> store.update(player, 0, 1L << 10));
            assertEquals(source, Files.readString(path));
        }
    }

    @Test void externallyChangedFilesCannotBeOverwrittenByAnOldSelection() throws IOException {
        var player = UUID.randomUUID(); var store = new JunkSlotStore(directory);
        var first = store.update(player, 0, 1L << 9);
        Files.writeString(directory.resolve(player + ".json"), "{\"schemaVersion\":1,\"slots\":[12]}");
        assertThrows(JunkSlotStore.Changed.class, () -> store.update(player, first.revision(), 1L << 10));
        assertEquals(1L << 12, store.read(player).mask());
    }

    @Test void runtimeSlotRolesNeverEnterTheExistingPreferencesProtocol() throws IOException {
        var config = new StorageConfig(); config.junkBoxSlots = 1L << 9;
        assertFalse(ConfigFile.options(config).has("junkBoxSlots"));
        assertThrows(IOException.class, () -> ConfigFile.parsePreferences("{\"junkBoxSlots\":512}"));
    }
}
