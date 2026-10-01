package dev.magicshulkerboxes;

import dev.magicshulkerboxes.client.PreferenceToggle;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PreferenceToggleTest {
    @Test void togglesEveryBooleanFromInheritedOrExplicitValueWithoutChangingOtherChoices() throws IOException {
        for (var entry : ConfigFile.options(new StorageConfig()).entrySet()) {
            if (!entry.getValue().getAsJsonPrimitive().isBoolean()) continue;
            var defaults = ConfigFile.parsePreferences("{\"" + entry.getKey() + "\":true}");
            var original = ConfigFile.parsePreferences("{\"makeSpaceMode\":\"DISABLED\"}");
            var first = PreferenceToggle.toggle(entry.getKey(), original, defaults);
            assertFalse(first.get(entry.getKey()).getAsBoolean(), "Inherited true toggles off");
            var second = PreferenceToggle.toggle(entry.getKey(), first, defaults);
            assertTrue(second.get(entry.getKey()).getAsBoolean(), "Explicit false toggles on");
            assertEquals("DISABLED", second.get("makeSpaceMode").getAsString());
            assertFalse(original.has(entry.getKey()), "Source snapshot is unchanged");
        }
    }
    @Test void offlineUsesCurrentDefaultsAndRejectsNonBooleanOrPolicyFields() throws IOException {
        assertTrue(PreferenceToggle.toggle("craftRefill", ConfigFile.parsePreferences("{}"), null).get("craftRefill").getAsBoolean());
        for (var key : new String[]{"makeSpaceMode", "allowPlayerSettings", "playerEditableSettings", "unknown"})
            assertThrows(IOException.class, () -> PreferenceToggle.toggle(key, ConfigFile.parsePreferences("{}"), null));
    }
}
