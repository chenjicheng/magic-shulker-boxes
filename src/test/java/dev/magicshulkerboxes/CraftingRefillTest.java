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

class CraftingRefillTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @Test void allMissingIngredientsAreRefilledTogether() {
        var inventory = new SimpleContainer(41); var grid = new SimpleContainer(4);
        inventory.setItem(9, box(new ItemStack(Items.COAL, 4), new ItemStack(Items.STICK, 6)));
        assertTrue(CraftingRefill.refill(inventory, grid, torch(), empty(4), enabledConfig()));
        assertTrue(grid.getItem(0).is(Items.COAL)); assertTrue(grid.getItem(2).is(Items.STICK));
        assertEquals(3, contents(inventory.getItem(9)).getFirst().getCount());
        assertEquals(5, contents(inventory.getItem(9)).get(1).getCount());
    }

    @Test void missingSecondIngredientDoesNotPartiallyExtractFirst() {
        var inventory = new SimpleContainer(41); var grid = new SimpleContainer(4);
        var source = box(new ItemStack(Items.COAL, 4)); inventory.setItem(9, source.copy());
        assertFalse(CraftingRefill.refill(inventory, grid, torch(), empty(4), enabledConfig()));
        assertTrue(grid.isEmpty()); assertTrue(ItemStack.matches(source, inventory.getItem(9)));
    }

    @Test void backpackIsPreferredAndExistingGridStacksStayIntact() {
        var inventory = new SimpleContainer(41); var grid = new SimpleContainer(4);
        var source = box(new ItemStack(Items.STICK, 6)); inventory.setItem(9, source.copy());
        inventory.setItem(10, new ItemStack(Items.STICK, 3)); grid.setItem(0, new ItemStack(Items.COAL, 7));
        assertTrue(CraftingRefill.refill(inventory, grid, torch(), empty(4), enabledConfig()));
        assertEquals(7, grid.getItem(0).getCount()); assertEquals(2, inventory.getItem(10).getCount());
        assertTrue(ItemStack.matches(source, inventory.getItem(9)));
    }

    @Test void aChangedGridIsNotOverwritten() {
        var inventory = new SimpleContainer(41); var grid = new SimpleContainer(4);
        inventory.setItem(9, box(new ItemStack(Items.COAL), new ItemStack(Items.STICK)));
        grid.setItem(0, new ItemStack(Items.DIAMOND));
        assertFalse(CraftingRefill.refill(inventory, grid, torch(), empty(4), enabledConfig()));
        assertTrue(grid.getItem(0).is(Items.DIAMOND)); assertEquals(2, contents(inventory.getItem(9)).size());
    }

    @Test void returnedBucketIsPreservedWhenRefillingItsIngredient() {
        var inventory = new SimpleContainer(41); var grid = new SimpleContainer(1);
        inventory.setItem(9, box(new ItemStack(Items.MILK_BUCKET))); grid.setItem(0, new ItemStack(Items.BUCKET));
        assertTrue(CraftingRefill.refill(inventory, grid, List.of(new ItemStack(Items.MILK_BUCKET)),
                List.of(new ItemStack(Items.BUCKET)), enabledConfig()));
        assertTrue(grid.getItem(0).is(Items.MILK_BUCKET)); assertTrue(inventory.getItem(10).is(Items.BUCKET));
    }

    @Test void fullBackpackCanExchangeRemainderIntoVacatedSourceBox() {
        var inventory = full(); var grid = new SimpleContainer(1);
        inventory.setItem(35, box(new ItemStack(Items.MILK_BUCKET))); grid.setItem(0, new ItemStack(Items.BUCKET));
        var config = enabledConfig(); config.refillMakeSpace = false;
        assertFalse(CraftingRefill.refill(inventory, grid, List.of(new ItemStack(Items.MILK_BUCKET)),
                List.of(new ItemStack(Items.BUCKET)), config));
        assertTrue(grid.getItem(0).is(Items.BUCKET));
        config.refillMakeSpace = true;
        assertTrue(CraftingRefill.refill(inventory, grid, List.of(new ItemStack(Items.MILK_BUCKET)),
                List.of(new ItemStack(Items.BUCKET)), config));
        assertTrue(grid.getItem(0).is(Items.MILK_BUCKET));
        assertTrue(contents(inventory.getItem(35)).getFirst().is(Items.BUCKET));
    }

    @Test void stackedBoxSplitsOneCopyAndKeepsAllOtherCopies() {
        var inventory = full(); var grid = new SimpleContainer(4);
        inventory.setItem(9, box(new ItemStack(Items.COAL, 2), new ItemStack(Items.STICK, 2)));
        inventory.getItem(9).setCount(3); inventory.setItem(10, ItemStack.EMPTY);
        assertTrue(CraftingRefill.refill(inventory, grid, torch(), empty(4), enabledConfig()));
        assertEquals(2, inventory.getItem(9).getCount());
        assertEquals(2, contents(inventory.getItem(9)).getFirst().getCount());
        assertEquals(1, contents(inventory.getItem(10)).getFirst().getCount());
        assertEquals(1, contents(inventory.getItem(10)).get(1).getCount());
    }

    @Test void switchIsIndependentAndFullComponentsSelectTheReplacement() {
        var inventory = new SimpleContainer(41); var grid = new SimpleContainer(1);
        var named = new ItemStack(Items.STONE); named.set(DataComponents.CUSTOM_NAME, Component.literal("Chosen"));
        inventory.setItem(9, box(new ItemStack(Items.STONE), named));
        var config = enabledConfig(); config.craftRefill = false;
        assertFalse(CraftingRefill.refill(inventory, grid, List.of(named), empty(1), config));
        config.craftRefill = true; config.pickupStorageEnabled = config.schematicRefill = config.ipnRefill = false;
        assertTrue(CraftingRefill.refill(inventory, grid, List.of(named), empty(1), config));
        assertTrue(ItemStack.isSameItemSameComponents(named, grid.getItem(0)));
        assertEquals(1, contents(inventory.getItem(9)).size());
    }

    private static StorageConfig enabledConfig() {
        var config = new StorageConfig();
        config.craftRefill = true;
        return config;
    }
    private static List<ItemStack> torch() {
        return List.of(new ItemStack(Items.COAL), ItemStack.EMPTY, new ItemStack(Items.STICK), ItemStack.EMPTY);
    }
    private static List<ItemStack> empty(int size) { return java.util.Collections.nCopies(size, ItemStack.EMPTY); }
    private static SimpleContainer full() {
        var inventory = new SimpleContainer(41);
        for (int i = 0; i < 36; i++) inventory.setItem(i, new ItemStack(Items.DIRT, 64)); return inventory;
    }
    private static ItemStack box(ItemStack... stacks) {
        var box = new ItemStack(Items.SHULKER_BOX); box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(stacks))); return box;
    }
    private static List<ItemStack> contents(ItemStack box) {
        return box.get(DataComponents.CONTAINER).stream().filter(stack -> !stack.isEmpty()).toList();
    }
}
