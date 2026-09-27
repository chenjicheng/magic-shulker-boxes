package dev.magicshulkerboxes;

import dev.magicshulkerboxes.client.PreferenceSync;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PreferenceSyncTest {
    @TempDir Path directory;

    @Test void unresolvedSaveSurvivesRestartAndIsScopedToServerAndPlayer() throws IOException {
        var file = directory.resolve("preferences.json");
        var recovery = directory.resolve("recovery");
        var sync = new PreferenceSync(file, recovery);
        ConfigFile.writePreferences(file, ConfigFile.parsePreferences("{\"schematicRefill\":true}"));
        sync.prepareSave("server-a/player-a");
        var restarted = new PreferenceSync(file, recovery);
        assertTrue(restarted.needsRecovery("server-a/player-a"));
        assertFalse(restarted.needsRecovery("server-b/player-a"));
        assertFalse(restarted.needsRecovery("server-a/player-b"));
        restarted.receive("server-a/player-a", ConfigFile.parsePreferences("{\"schematicRefill\":false}"));
        assertFalse(restarted.needsRecovery("server-a/player-a"));
        assertFalse(restarted.values().get("schematicRefill").getAsBoolean());
        assertFalse(ConfigFile.readPreferences(file).get("schematicRefill").getAsBoolean());
    }

    @Test void failedLocalWriteStillUpdatesActiveValuesAndRequiresRecovery() throws IOException {
        var file = directory.resolve("preferences.json");
        var recovery = directory.resolve("recovery");
        var sync = new PreferenceSync(file, recovery);
        sync.prepareSave("server/player");
        // A nonempty directory at the destination forces the atomic file replacement to fail.
        Files.createDirectory(file); Files.writeString(file.resolve("occupied"), "keep");
        assertThrows(IOException.class, () -> sync.receive("server/player",
                ConfigFile.parsePreferences("{\"schematicRefill\":false}")));
        assertFalse(sync.values().get("schematicRefill").getAsBoolean(), "Current session follows the confirmed server value");
        assertTrue(sync.needsRecovery("server/player"));
        assertTrue(new PreferenceSync(file, recovery).needsRecovery("server/player"), "Restart cannot auto-upload the stale file");
        sync.values().addProperty("schematicRefill", true);
        assertFalse(sync.values().get("schematicRefill").getAsBoolean(), "UI receives a detached snapshot");
    }

    @Test void failedRecoveryWritePreventsSendingASaveAndDisconnectClearsActiveValues() throws IOException {
        var file = directory.resolve("preferences.json");
        var blocked = directory.resolve("blocked"); Files.writeString(blocked, "not a directory");
        var sync = new PreferenceSync(file, blocked);
        assertThrows(IOException.class, () -> sync.prepareSave("server/player"));
        var normal = new PreferenceSync(file, directory.resolve("recovery"));
        normal.receive("server/player", ConfigFile.parsePreferences("{\"enabled\":false}"));
        ConfigFile.writePreferences(file, ConfigFile.parsePreferences("{\"enabled\":true}"));
        normal.disconnected();
        assertTrue(normal.values().get("enabled").getAsBoolean());
    }
}
