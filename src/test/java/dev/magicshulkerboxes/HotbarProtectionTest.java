package dev.magicshulkerboxes;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HotbarProtectionTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @Test void anEnabledHotbarStillSkipsThePlayersProtectedPositions() throws Exception {
        var config = ConfigFile.parseServer("{\"useHotbarForSpace\":true,\"protectHotbarSlot1\":true,\"protectHotbarSlot9\":true}");
        var inventory = new SimpleContainer(36);
        inventory.setItem(0, new ItemStack(Items.STONE, 3));
        inventory.setItem(1, new ItemStack(Items.STONE, 4));
        inventory.setItem(8, new ItemStack(Items.STONE, 5));
        inventory.setItem(9, ManagedJunkStorageTest.box());
        long eligible = (1L << 0) | (1L << 1) | (1L << 8) | (1L << 9);
        assertEquals(1, BoxRelocation.makeSpace(inventory, config, eligible, new ItemStack(Items.STONE), null));
        assertEquals(3, inventory.getItem(0).getCount());
        assertEquals(5, inventory.getItem(8).getCount());
        assertTrue(inventory.getItem(1).isEmpty());
    }

    @Test void protectionCanBeSavedWithoutEnablingHotbarRelocation() throws Exception {
        var config = ConfigFile.parseServer("{\"protectHotbarSlot1\":true,\"protectHotbarSlot3\":true,\"protectHotbarSlot9\":true}");
        assertFalse(config.useHotbarForSpace);
        var values = ConfigFile.parsePreferences("{\"protectHotbarSlot1\":true,\"protectHotbarSlot3\":true,\"protectHotbarSlot9\":true}");
        assertTrue(values.get("protectHotbarSlot1").getAsBoolean());
        assertTrue(values.get("protectHotbarSlot3").getAsBoolean());
        assertTrue(values.get("protectHotbarSlot9").getAsBoolean());
    }

    @Test void aProtectedOnlyPlanLeavesEverythingInPlace() throws Exception {
        var config = ConfigFile.parseServer("{\"useHotbarForSpace\":true,\"protectHotbarSlot1\":true}");
        var inventory = new SimpleContainer(36);
        inventory.setItem(0, new ItemStack(Items.STONE, 3));
        inventory.setItem(9, ManagedJunkStorageTest.box());
        var original = CraftingMaterials.copy(inventory);
        assertEquals(-1, BoxRelocation.makeSpace(inventory, config, (1L << 0) | (1L << 9), new ItemStack(Items.STONE), null));
        for (int slot = 0; slot < 36; slot++) assertTrue(ItemStack.matches(original.getItem(slot), inventory.getItem(slot)));
    }

    @Test void nonBooleanProtectionValuesAreRejectedAtTheSettingsBoundary() {
        for (String positions : new String[] {"0", "1", "9", "\"true\"", "[]", "null"}) {
            assertThrows(java.io.IOException.class,
                    () -> ConfigFile.parsePreferences("{\"protectHotbarSlot1\":" + positions + "}"));
        }
    }
}
