package dev.magicshulkerboxes;

import java.util.Set;
import java.util.Map;
import java.util.HashMap;
import java.io.InputStreamReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import com.google.gson.JsonParser;
import net.minecraft.network.chat.Component;

public final class Messages {
    private Messages() {}
    private static final String PREFIX = "magic_shulker_boxes.";
    private static final Map<String, String> ENGLISH = load("en_us");
    private static final Map<String, String> CHINESE = load("zh_cn");

    private static Map<String, String> load(String language) {
        var path = "/assets/magic_shulker_boxes/lang/" + language + ".json";
        try (var stream = Messages.class.getResourceAsStream(path)) {
            if (stream == null) throw new IllegalStateException("Missing language resource: " + path);
            var json = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            Map<String, String> values = new HashMap<>();
            json.entrySet().forEach(entry -> values.put(entry.getKey().substring(PREFIX.length()), entry.getValue().getAsString()));
            return Map.copyOf(values);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read language resource: " + path, exception);
        }
    }

    public static String pattern(String language, String key) {
        var table = language != null && language.startsWith("zh_") ? CHINESE : ENGLISH;
        return table.getOrDefault(key, ENGLISH.getOrDefault(key, PREFIX + key));
    }
    public static Set<String> keys(String language) { return language.equals("zh_cn") ? CHINESE.keySet() : ENGLISH.keySet(); }

    // Clients without this mod still receive a readable fallback in their reported game language.
    public static Component text(String language, String key, Object... arguments) {
        return Component.translatableWithFallback(PREFIX + key, pattern(language, key), arguments);
    }
}
