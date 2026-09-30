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

class ShulkerRestockTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @Test void externalRestockIsIndependentOfPickupSchematicAndOneItemMode() {
        var inventory = new SimpleContainer(41);
        inventory.setItem(9, box(new ItemStack(Items.GOLDEN_CARROT, 32)));
        var config = new StorageConfig();
        config.pickupStorageEnabled = false;
        config.schematicRefill = false;
        config.refillFullStack = false;
        assertEquals(32, ShulkerRefill.takeForRestock(inventory, 9, 0, config, mask(10)));
        assertEquals(32, inventory.getItem(10).getCount());
        assertTrue(contents(inventory.getItem(9)).isEmpty());
    }

    @Test void selectedToolRetainsDamageNameAndAllComponents() {
        var inventory = new SimpleContainer(41);
        var tool = new ItemStack(Items.DIAMOND_PICKAXE);
        tool.setDamageValue(15);
        tool.set(DataComponents.CUSTOM_NAME, Component.literal("Spare"));
        inventory.setItem(9, box(tool));
        assertEquals(1, ShulkerRefill.takeForRestock(inventory, 9, 0, new StorageConfig(), mask(14)));
        assertTrue(ItemStack.matches(tool, inventory.getItem(14)));
        assertTrue(inventory.getItem(10).isEmpty());
    }

    @Test void disabledRestockAndExcludedDestinationsNeverChangeItems() {
        var inventory = new SimpleContainer(41);
        var original = box(new ItemStack(Items.POTION));
        inventory.setItem(9, original.copy());
        var config = new StorageConfig(); config.ipnRefill = false;
        assertEquals(0, ShulkerRefill.takeForRestock(inventory, 9, 0, config, mask(10)));
        config.ipnRefill = true;
        assertEquals(0, ShulkerRefill.takeForRestock(inventory, 9, 0, config, 0));
        assertTrue(ItemStack.matches(original, inventory.getItem(9)));
    }

    @Test void fullBackpackUsesOnlyExternallyEligibleSlotsAndKeepsHandUntouched() {
        var inventory = full();
        inventory.setItem(9, box(new ItemStack(Items.POTION)));
        var hand = inventory.getItem(0).copy();
        assertEquals(1, ShulkerRefill.takeForRestock(inventory, 9, 0, new StorageConfig(), mask(9, 20)));
        assertTrue(inventory.getItem(20).is(Items.POTION));
        assertTrue(ItemStack.matches(hand, inventory.getItem(0)));
        assertEquals(64, contents(inventory.getItem(9)).getFirst().getCount());
        for (int i = 10; i < 36; i++) if (i != 20) assertTrue(inventory.getItem(i).is(Items.DIRT));
    }

    @Test void emptyHotbarDoesNotMakeAnUnusableBackpackLookUsable() {
        var inventory = full();
        inventory.setItem(0, ItemStack.EMPTY);
        inventory.setItem(9, box(new ItemStack(Items.POTION)));
        var config = new StorageConfig(); config.refillMakeSpace = false;
        assertEquals(0, ShulkerRefill.takeForRestock(inventory, 9, 0, config, mask(10)));
        assertTrue(inventory.getItem(0).isEmpty());
        assertTrue(contents(inventory.getItem(9)).getFirst().is(Items.POTION));
    }

    @Test void stackedBoxNeedsTwoEligibleSlotsAndSplitsExactlyOnce() {
        var inventory = full();
        inventory.setItem(9, box(new ItemStack(Items.POTION)));
        inventory.getItem(9).setCount(3);
        inventory.setItem(0, ItemStack.EMPTY);
        inventory.setItem(10, ItemStack.EMPTY);
        inventory.setItem(11, ItemStack.EMPTY);
        assertEquals(0, ShulkerRefill.takeForRestock(inventory, 9, 0, new StorageConfig(), mask(10)));
        assertEquals(3, inventory.getItem(9).getCount());
        assertEquals(1, ShulkerRefill.takeForRestock(inventory, 9, 0, new StorageConfig(), mask(10, 11)));
        assertEquals(2, inventory.getItem(9).getCount());
        assertTrue(inventory.getItem(10).is(Items.POTION));
        assertTrue(ShulkerStorage.isShulker(inventory.getItem(11)));
        assertTrue(inventory.getItem(0).isEmpty());
    }

    @Test void invalidSourcesAndMasksAreRejectedWithoutMutation() {
        var inventory = new SimpleContainer(41);
        for (int slot : new int[]{0, 8, 36, 40}) {
            var original = box(new ItemStack(Items.POTION));
            inventory.setItem(slot, original.copy());
            assertEquals(0, ShulkerRefill.takeForRestock(inventory, slot, 0, new StorageConfig(), mask(10)));
            assertTrue(ItemStack.matches(original, inventory.getItem(slot)));
        }
        inventory.setItem(9, box(new ItemStack(Items.POTION)));
        assertEquals(0, ShulkerRefill.takeForRestock(inventory, 9, 0, new StorageConfig(), -1));
        assertEquals(0, ShulkerRefill.takeForRestock(inventory, 9, 27, new StorageConfig(), mask(10)));
    }

    static int mask(int... slots) {
        int result = 0;
        for (int slot : slots) result |= 1 << (slot - 9);
        return result;
    }
    private static SimpleContainer full() {
        var inventory = new SimpleContainer(41);
        for (int i = 0; i < 36; i++) inventory.setItem(i, new ItemStack(Items.DIRT, 64));
        return inventory;
    }
    private static ItemStack box(ItemStack... stacks) {
        var box = new ItemStack(Items.SHULKER_BOX);
        box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(stacks)));
        return box;
    }
    private static List<ItemStack> contents(ItemStack box) {
        return box.get(DataComponents.CONTAINER).stream().filter(stack -> !stack.isEmpty()).toList();
    }
}
