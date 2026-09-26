package dev.magicshulkerboxes.client;

import com.google.gson.JsonObject;
import dev.magicshulkerboxes.ConfigFile;

/** A screen owns a detached draft. Only Save may commit it. */
public final class SettingsDraft {
    public enum Toggle { INHERIT, ON, OFF }
    public enum Space { INHERIT, DISABLED, MOVE_TO_BOX, DROP_AND_PICKUP }
    private final JsonObject overrides;
    public SettingsDraft(JsonObject overrides) { this.overrides = overrides.deepCopy(); }
    public Toggle toggle(String key) {
        checkToggle(key);
        return !overrides.has(key) ? Toggle.INHERIT : overrides.get(key).getAsBoolean() ? Toggle.ON : Toggle.OFF;
    }
    public void toggle(String key, Toggle value) {
        checkToggle(key);
        if (value == Toggle.INHERIT) overrides.remove(key);
        else overrides.addProperty(key, value == Toggle.ON);
    }
    private static void checkToggle(String key) {
        if (!ConfigFile.optionNames().contains(key) || key.equals("makeSpaceMode")) throw new IllegalArgumentException(key);
    }
    public Space space() { return overrides.has("makeSpaceMode") ? Space.valueOf(overrides.get("makeSpaceMode").getAsString()) : Space.INHERIT; }
    public void space(Space value) {
        if (value == Space.INHERIT) overrides.remove("makeSpaceMode");
        else overrides.addProperty("makeSpaceMode", value.name());
    }
    public JsonObject values() { return overrides.deepCopy(); }
}
