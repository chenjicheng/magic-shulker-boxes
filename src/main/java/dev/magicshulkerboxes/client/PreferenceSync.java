package dev.magicshulkerboxes.client;

import com.google.common.hash.Hashing;
import com.google.gson.JsonObject;
import dev.magicshulkerboxes.ConfigFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/** Keeps confirmed session values separate from disk persistence and unresolved server saves. */
public final class PreferenceSync {
    private final Path preferences, recoveryDirectory;
    private final Set<String> uncertain = new HashSet<>();
    private JsonObject confirmed;

    public PreferenceSync(Path preferences, Path recoveryDirectory) {
        this.preferences = preferences;
        this.recoveryDirectory = recoveryDirectory;
    }

    private Path marker(String server) {
        return recoveryDirectory.resolve(Hashing.sha256().hashString(server, StandardCharsets.UTF_8) + ".json");
    }

    /** Must succeed before sending a save, so reconnect/crash cannot upload an obsolete local file. */
    public void prepareSave(String server) throws IOException {
        ConfigFile.write(marker(server), new JsonObject());
        uncertain.add(server);
    }

    public boolean needsRecovery(String server) {
        // Unknown filesystem access is treated as unresolved, never as permission to upload stale values.
        return uncertain.contains(server) || !Files.notExists(marker(server));
    }

    public JsonObject values() throws IOException {
        return confirmed == null ? ConfigFile.readPreferences(preferences) : confirmed.deepCopy();
    }

    public void receive(String server, JsonObject values) throws IOException {
        confirmed = ConfigFile.parsePreferences(values.toString());
        uncertain.add(server);
        // Publish the server value in memory even if either local write subsequently fails.
        ConfigFile.write(marker(server), new JsonObject());
        ConfigFile.write(preferences, confirmed);
        Files.deleteIfExists(marker(server));
        uncertain.remove(server);
    }

    public void disconnected() { confirmed = null; }
}
