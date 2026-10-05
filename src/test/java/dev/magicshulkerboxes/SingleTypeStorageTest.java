package dev.magicshulkerboxes;

import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static dev.magicshulkerboxes.ShulkerStorageTest.*;
import static org.junit.jupiter.api.Assertions.*;

class SingleTypeStorageTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @Test void pickupDoesNotUseBoxesContainingUnrelatedItems() {
        var inventory = fullInventory();
        var original = box(new ItemStack(Items.STONE), new ItemStack(Items.DIRT));
        inventory.setItem(0, original.copy());
        var config = pickup();
        config.makeSpaceMode = StorageConfig.MakeSpaceMode.DISABLED;
        var incoming = new ItemStack(Items.STONE, 5);
        assertEquals(0, ShulkerStorage.store(inventory, incoming, config));
        assertEquals(5, incoming.getCount());
        assertTrue(ItemStack.matches(original, inventory.getItem(0)));
    }

    @Test void pickupMakesSeparateBoxesForDisplacedAndIncomingTypes() {
        var inventory = fullInventory();
        inventory.setItem(0, box());
        inventory.getItem(0).setCount(16);
        inventory.setItem(9, new ItemStack(Items.STONE, 3));
        assertEquals(5, ShulkerStorage.store(inventory, new ItemStack(Items.COBBLESTONE, 5), pickup()));
        assertEquals(14, inventory.getItem(0).getCount());
        assertContents(inventory.getItem(9), Items.STONE, 67);
        assertContents(inventory.getItem(10), Items.COBBLESTONE, 5);
    }

    @Test void schematicRelocationKeepsRemainingSourceMaterialsSeparate() {
        var inventory = fullInventory();
        inventory.setItem(35, box(new ItemStack(Items.COBBLESTONE, 12)));
        inventory.setItem(34, box(new ItemStack(Items.STONE, 60)));
        var config = new StorageConfig();
        config.refillFullStack = false;
        assertEquals(1, ShulkerRefill.take(inventory, 35, 0, Items.COBBLESTONE, config));
        assertContents(inventory.getItem(35), Items.COBBLESTONE, 11);
        assertContents(inventory.getItem(34), Items.STONE, 124);
        assertTrue(inventory.getItem(9).is(Items.COBBLESTONE));
    }

    @Test void ipnCannotStoreDisplacedItemsInALockedBox() {
        var inventory = fullInventory();
        inventory.setItem(9, box(new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.COBBLESTONE)));
        inventory.setItem(34, box(new ItemStack(Items.STONE)));
        var source = inventory.getItem(9).copy();
        assertEquals(0, ShulkerRefill.takeForRestock(inventory, 9, 0, new StorageConfig(), ShulkerRestockTest.mask(9, 20)));
        assertTrue(ItemStack.matches(source, inventory.getItem(9)));
        assertContents(inventory.getItem(34), Items.STONE, 1);
        assertEquals(64, ShulkerRefill.takeForRestock(inventory, 9, 0, new StorageConfig(), ShulkerRestockTest.mask(9, 20, 34)));
        assertContents(inventory.getItem(9), Items.COBBLESTONE, 1);
        assertContents(inventory.getItem(34), Items.STONE, 65);
    }

    @Test void craftingRemainderUsesItsOwnBoxInsteadOfMixingWithIngredients() {
        var inventory = fullInventory();
        var grid = new SimpleContainer(1);
        inventory.setItem(35, box(new ItemStack(Items.MILK_BUCKET), new ItemStack(Items.MILK_BUCKET)));
        inventory.setItem(34, box(new ItemStack(Items.BUCKET)));
        grid.setItem(0, new ItemStack(Items.BUCKET));
        assertTrue(CraftingRefill.refill(inventory, grid, List.of(new ItemStack(Items.MILK_BUCKET)),
                List.of(new ItemStack(Items.BUCKET)), new StorageConfig()));
        assertContents(inventory.getItem(35), Items.MILK_BUCKET, 1);
        assertContents(inventory.getItem(34), Items.BUCKET, 2);
        assertTrue(grid.getItem(0).is(Items.MILK_BUCKET));
    }

    @Test void toolExchangeKeepsUnrelatedSpareToolsSeparate() {
        var inventory = fullInventory();
        inventory.setItem(0, new ItemStack(Items.DIAMOND_PICKAXE));
        inventory.setItem(9, box(new ItemStack(Items.IRON_PICKAXE), new ItemStack(Items.IRON_PICKAXE)));
        inventory.setItem(34, box());
        assertEquals(1, ShulkerRefill.swapToolForRestock(inventory, 9, 0, 0, new StorageConfig(), ShulkerRestockTest.mask(9, 34)));
        assertContents(inventory.getItem(9), Items.IRON_PICKAXE, 1);
        assertContents(inventory.getItem(34), Items.DIAMOND_PICKAXE, 1);
    }

    @Test void ordinaryCraftingIngredientsUseTheSameRemainderStorageRule() {
        var inventory = fullInventory();
        var grid = new SimpleContainer(1);
        inventory.setItem(9, new ItemStack(Items.HONEY_BOTTLE, 16));
        inventory.setItem(34, box(new ItemStack(Items.GLASS_BOTTLE)));
        grid.setItem(0, new ItemStack(Items.GLASS_BOTTLE));
        assertTrue(CraftingRefill.refill(inventory, grid, List.of(new ItemStack(Items.HONEY_BOTTLE)),
                List.of(new ItemStack(Items.GLASS_BOTTLE)), new StorageConfig()));
        assertEquals(15, inventory.getItem(9).getCount());
        assertContents(inventory.getItem(34), Items.GLASS_BOTTLE, 2);
        assertTrue(grid.getItem(0).is(Items.HONEY_BOTTLE));
    }

    private static StorageConfig pickup() {
        var config = new StorageConfig(); config.pickupStorageEnabled = true; return config;
    }

    static void assertContents(ItemStack box, net.minecraft.world.item.Item item, int count) {
        var contents = box.get(DataComponents.CONTAINER);
        assertTrue(contents.stream().allMatch(stack -> stack.isEmpty() || stack.is(item)));
        assertEquals(count, countContents(box));
    }
}
