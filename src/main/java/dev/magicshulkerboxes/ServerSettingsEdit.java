package dev.magicshulkerboxes;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Set;

/** Validate a complete replacement before any server file or active setting changes. */
public final class ServerSettingsEdit {
    private ServerSettingsEdit() {}
    private static JsonObject options(ServerConfig config) { return JsonParser.parseString(ConfigFile.json(config)).getAsJsonObject(); }
    public static Set<String> optionNames() { return options(new ServerConfig()).keySet(); }

    public static ServerConfig edit(ServerConfig current, String key, String value) throws IOException {
        var replacement = options(current);
        if (!replacement.has(key)) throw new IOException("Unknown server option: " + key);
        try {
            if (value == null) replacement.add(key, options(new ServerConfig()).get(key));
            else if (key.equals("makeSpaceMode")) replacement.addProperty(key, value);
            else if (key.equals("playerEditableSettings")) replacement.add(key, JsonParser.parseString(value));
            else if (value.equals("true") || value.equals("false")) replacement.addProperty(key, Boolean.parseBoolean(value));
            else throw new IOException("Invalid boolean: " + value);
            return ConfigFile.parseServer(replacement.toString());
        } catch (JsonParseException exception) { throw new IOException("Invalid server setting", exception); }
    }

    public static ServerConfig permission(ServerConfig current, String option, boolean allowed) throws IOException {
        if (!ConfigFile.optionNames().contains(option)) throw new IOException("Unknown player option: " + option);
        var names = new ArrayList<>(current.playerEditableSettings);
        if (allowed && !names.contains(option)) names.add(option);
        if (!allowed) names.remove(option);
        return edit(current, "playerEditableSettings", ConfigFile.json(names));
    }
}
