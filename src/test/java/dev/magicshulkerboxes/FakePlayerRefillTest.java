package dev.magicshulkerboxes;

import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FakePlayerRefillTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @Test void emptyMainAndOffhandReceiveExactlyOneSourceStack() {
        for (int target : new int[]{0, 40}) {
            var inventory = new SimpleContainer(41);
            inventory.setItem(9, box(new ItemStack(Items.COBBLESTONE, 12)));
            assertEquals(12, FakePlayerRefill.restock(inventory, target, new ItemStack(Items.COBBLESTONE), new StorageConfig()));
            assertEquals(12, inventory.getItem(target).getCount());
            assertTrue(stored(inventory.getItem(9)).isEmpty());
        }
    }

    @Test void looseBackpackSuppliesComeBeforeBoxesAndOtherSwitchesAreIndependent() {
        var inventory = new SimpleContainer(41);
        inventory.setItem(9, box(new ItemStack(Items.COBBLESTONE, 12)));
        inventory.setItem(10, new ItemStack(Items.COBBLESTONE, 5));
        var config = new StorageConfig();
        config.pickupStorageEnabled = config.schematicRefill = config.ipnRefill = config.craftRefill = false;
        assertEquals(5, FakePlayerRefill.restock(inventory, 0, new ItemStack(Items.COBBLESTONE), config));
        assertEquals(12, stored(inventory.getItem(9)).getFirst().getCount());
        assertTrue(inventory.getItem(10).isEmpty());
    }

    @Test void componentMismatchNeverReplacesNamedItems() {
        var inventory = new SimpleContainer(41);
        var wanted = new ItemStack(Items.POTION);
        wanted.set(DataComponents.CUSTOM_NAME, Component.literal("Wanted"));
        inventory.setItem(9, box(new ItemStack(Items.POTION)));
        assertEquals(0, FakePlayerRefill.restock(inventory, 0, wanted, new StorageConfig()));
        inventory.setItem(10, box(wanted.copy()));
        assertEquals(1, FakePlayerRefill.restock(inventory, 0, wanted, new StorageConfig()));
        assertTrue(ItemStack.matches(wanted, inventory.getItem(0)));
        assertEquals(1, stored(inventory.getItem(9)).size());
    }

    @Test void replacementToolsIgnoreOnlyDamageAndRetainTheSpareComponents() {
        var inventory = new SimpleContainer(41);
        var broken = new ItemStack(Items.DIAMOND_PICKAXE);
        broken.setDamageValue(broken.getMaxDamage() - 1);
        broken.set(DataComponents.CUSTOM_NAME, Component.literal("Mine"));
        var spare = broken.copy(); spare.setDamageValue(15);
        inventory.setItem(9, box(new ItemStack(Items.DIAMOND_PICKAXE)));
        inventory.setItem(10, box(spare.copy()));
        assertEquals(1, FakePlayerRefill.restock(inventory, 0, broken, new StorageConfig()));
        assertTrue(ItemStack.matches(spare, inventory.getItem(0)));
        assertEquals(1, stored(inventory.getItem(9)).size());
    }

    @Test void consumedPotionPreservesBottleEvenWithFullBackpack() {
        var inventory = full();
        inventory.setItem(0, new ItemStack(Items.GLASS_BOTTLE));
        inventory.setItem(9, box(new ItemStack(Items.POTION)));
        assertEquals(1, FakePlayerRefill.restock(inventory, 0, new ItemStack(Items.POTION), new StorageConfig()));
        assertTrue(inventory.getItem(0).is(Items.POTION));
        assertTrue(stored(inventory.getItem(9)).getFirst().is(Items.GLASS_BOTTLE));
        assertEquals(1, stored(inventory.getItem(9)).getFirst().getCount());
    }

    @Test void failureToPreserveBottleRollsBackEntireExtraction() {
        var inventory = full();
        inventory.setItem(0, new ItemStack(Items.GLASS_BOTTLE));
        var source = box(new ItemStack(Items.POTION)); inventory.setItem(9, source.copy());
        var config = new StorageConfig(); config.refillMakeSpace = false;
        assertEquals(0, FakePlayerRefill.restock(inventory, 0, new ItemStack(Items.POTION), config));
        assertTrue(inventory.getItem(0).is(Items.GLASS_BOTTLE));
        assertTrue(ItemStack.matches(source, inventory.getItem(9)));
    }

    @Test void stackedBoxesNeedTheirOwnFreeSlotAndNeverOverwriteTheHand() {
        var inventory = full();
        inventory.setItem(0, ItemStack.EMPTY);
        var source = box(new ItemStack(Items.COBBLESTONE, 12));
        inventory.setItem(9, source.copy()); inventory.getItem(9).setCount(3);
        assertEquals(0, FakePlayerRefill.restock(inventory, 0, new ItemStack(Items.COBBLESTONE), new StorageConfig()));
        assertEquals(3, inventory.getItem(9).getCount());
        inventory.setItem(10, ItemStack.EMPTY);
        assertEquals(12, FakePlayerRefill.restock(inventory, 0, new ItemStack(Items.COBBLESTONE), new StorageConfig()));
        assertEquals(2, inventory.getItem(9).getCount());
        assertTrue(ItemStack.matches(source, inventory.getItem(9).copyWithCount(1)));
        assertTrue(ShulkerStorage.isShulker(inventory.getItem(10)));
        assertTrue(stored(inventory.getItem(10)).isEmpty());
        assertEquals(12, inventory.getItem(0).getCount());
    }

    @Test void disabledAndUnexhaustedHandsNeverTouchSources() {
        var inventory = new SimpleContainer(41);
        var source = box(new ItemStack(Items.COBBLESTONE, 12)); inventory.setItem(9, source.copy());
        var config = new StorageConfig(); config.carpetRefill = false;
        assertEquals(0, FakePlayerRefill.restock(inventory, 0, new ItemStack(Items.COBBLESTONE), config));
        config.carpetRefill = true;
        inventory.setItem(0, new ItemStack(Items.COBBLESTONE));
        assertEquals(0, FakePlayerRefill.restock(inventory, 0, new ItemStack(Items.COBBLESTONE), config));
        inventory.setItem(0, new ItemStack(Items.DIAMOND));
        assertEquals(0, FakePlayerRefill.restock(inventory, 0, new ItemStack(Items.COBBLESTONE), config));
        assertTrue(ItemStack.matches(source, inventory.getItem(9)));
    }

    @Test void offhandSourceAndOneItemRefillFollowExistingSettings() {
        var inventory = new SimpleContainer(41);
        inventory.setItem(40, box(new ItemStack(Items.COBBLESTONE, 12)));
        var config = new StorageConfig(); config.refillFullStack = false;
        assertEquals(0, FakePlayerRefill.restock(inventory, 0, new ItemStack(Items.COBBLESTONE), config));
        config.includeOffhand = true;
        assertEquals(1, FakePlayerRefill.restock(inventory, 0, new ItemStack(Items.COBBLESTONE), config));
        assertEquals(11, stored(inventory.getItem(40)).getFirst().getCount());
    }

    @Test void fakeRefillHasIndependentDefaultsAndPersonalOverrides() throws Exception {
        var defaults = new StorageConfig();
        assertTrue(defaults.carpetRefill);
        defaults.carpetRefill = false;
        assertTrue(ConfigFile.apply(defaults, ConfigFile.parsePreferences("{\"carpetRefill\":true}")).carpetRefill);
        assertFalse(ConfigFile.apply(defaults, ConfigFile.parsePreferences("{}")).carpetRefill);
    }

    private static SimpleContainer full() {
        var inventory = new SimpleContainer(41);
        for (int slot = 0; slot < 36; slot++) inventory.setItem(slot, new ItemStack(Items.DIRT, 64));
        return inventory;
    }
    private static ItemStack box(ItemStack contents) {
        var box = new ItemStack(Items.SHULKER_BOX);
        box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(contents)));
        return box;
    }
    private static List<ItemStack> stored(ItemStack box) {
        return box.get(DataComponents.CONTAINER).stream().filter(stack -> !stack.isEmpty()).toList();
    }
}
