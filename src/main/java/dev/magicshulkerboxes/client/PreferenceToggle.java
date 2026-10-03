package dev.magicshulkerboxes.client;

import com.google.gson.JsonObject;
import dev.magicshulkerboxes.ConfigFile;
import dev.magicshulkerboxes.StorageConfig;
import java.io.IOException;

public final class PreferenceToggle {
    private PreferenceToggle() {}
    public static JsonObject toggle(String key, JsonObject overrides, JsonObject serverDefaults) throws IOException {
        var fallback = ConfigFile.options(new StorageConfig()).get(key);
        if (fallback == null || !fallback.isJsonPrimitive() || !fallback.getAsJsonPrimitive().isBoolean())
            throw new IOException("Not a personal boolean setting: " + key);
        var result = ConfigFile.parsePreferences(overrides.toString());
        var inherited = serverDefaults != null && serverDefaults.has(key) ? serverDefaults.get(key) : fallback;
        var current = result.has(key) ? result.get(key) : inherited;
        result.addProperty(key, !current.getAsBoolean());
        return result;
    }
}
