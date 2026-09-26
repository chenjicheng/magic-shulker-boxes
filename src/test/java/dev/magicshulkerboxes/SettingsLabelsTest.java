package dev.magicshulkerboxes;

import com.google.gson.JsonObject;
import dev.magicshulkerboxes.client.SettingsLabels;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SettingsLabelsTest {
    @Test void inheritedBooleanShowsReceivedValueAndTracksUpdates() {
        var defaults = new JsonObject(); defaults.addProperty("schematicRefill", true);
        assertInherited("gui.toggle.ON", SettingsLabels.personal("schematicRefill", "INHERIT", defaults));
        defaults.addProperty("schematicRefill", false);
        assertInherited("gui.toggle.OFF", SettingsLabels.personal("schematicRefill", "INHERIT", defaults));
        assertEquals(1, defaults.size());
    }

    @Test void inheritedEnumShowsTheActualServerMode() {
        var defaults = new JsonObject(); defaults.addProperty("makeSpaceMode", "DROP_AND_PICKUP");
        assertInherited("gui.space.DROP_AND_PICKUP", SettingsLabels.personal("makeSpaceMode", "INHERIT", defaults));
    }

    @Test void unsynchronizedDefaultsAreExplicitlyUnknown() {
        for (String key : new String[]{"schematicRefill", "makeSpaceMode"}) {
            assertEquals("magic_shulker_boxes.gui.inherit_unknown", contents(SettingsLabels.personal(key, "INHERIT", null)).getKey());
            assertEquals("magic_shulker_boxes.gui.inherit_unknown", contents(SettingsLabels.personal(key, "INHERIT", new JsonObject())).getKey());
        }
    }

    @Test void personalOverridesKeepTheirOwnValue() {
        var defaults = new JsonObject(); defaults.addProperty("schematicRefill", false);
        defaults.addProperty("makeSpaceMode", "DISABLED");
        assertEquals("magic_shulker_boxes.gui.toggle.ON", contents(SettingsLabels.personal("schematicRefill", "ON", defaults)).getKey());
        assertEquals("magic_shulker_boxes.gui.space.MOVE_TO_BOX", contents(SettingsLabels.personal("makeSpaceMode", "MOVE_TO_BOX", defaults)).getKey());
    }

    private static TranslatableContents contents(Component value) { return (TranslatableContents) value.getContents(); }
    private static void assertInherited(String expected, Component value) {
        var translated = contents(value);
        assertEquals("magic_shulker_boxes.gui.inherit_value", translated.getKey());
        assertEquals("magic_shulker_boxes." + expected, contents((Component) translated.getArgs()[0]).getKey());
    }
}
