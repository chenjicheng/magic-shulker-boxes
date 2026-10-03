package dev.magicshulkerboxes.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.magicshulkerboxes.ConfigFile;
import dev.magicshulkerboxes.StorageConfig;
import java.util.LinkedHashMap;
import java.util.Map;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

/** Physical bindings stay in vanilla client options; only preference values are synchronized. */
public final class SettingsKeybindings {
    private SettingsKeybindings() {}
    public static void register() {
        var category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("magic_shulker_boxes", "settings"));
        Map<String, KeyMapping> mappings = new LinkedHashMap<>();
        ConfigFile.options(new StorageConfig()).entrySet().stream()
                .filter(entry -> entry.getValue().isJsonPrimitive() && entry.getValue().getAsJsonPrimitive().isBoolean())
                .forEach(entry -> mappings.put(entry.getKey(), KeyBindingHelper.registerKeyBinding(new KeyMapping(
                        "key.magic_shulker_boxes.toggle." + entry.getKey(), InputConstants.UNKNOWN.getValue(), category))));
        ClientTickEvents.END_CLIENT_TICK.register(client -> mappings.forEach((option, binding) -> {
            while (binding.consumeClick()) if (client.screen == null) ClientSettings.toggle(option);
        }));
    }
}
