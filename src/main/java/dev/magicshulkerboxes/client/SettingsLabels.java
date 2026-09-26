package dev.magicshulkerboxes.client;

import com.google.gson.JsonObject;
import net.minecraft.network.chat.Component;

/** Value labels keep an inherited preference distinct from a personal override. */
public final class SettingsLabels {
    private SettingsLabels() {}
    public static Component personal(String key, String selection, JsonObject defaults) {
        String kind = key.equals("makeSpaceMode") ? "space." : "toggle.";
        if (!selection.equals("INHERIT")) return text("gui." + kind + selection);
        if (defaults == null || !defaults.has(key)) return text("gui.inherit_unknown");
        String value = key.equals("makeSpaceMode") ? defaults.get(key).getAsString()
                : defaults.get(key).getAsBoolean() ? "ON" : "OFF";
        return text("gui.inherit_value", text("gui." + kind + value));
    }
    private static Component text(String key, Object... args) {
        return Component.translatable("magic_shulker_boxes." + key, args);
    }
}
