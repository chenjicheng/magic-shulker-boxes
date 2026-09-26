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

class ShulkerRefillTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @Test void extractsAnExistingStackWithoutLosingComponents() {
        var inv = new SimpleContainer(41);
        var stone = new ItemStack(Items.STONE, 37);
        stone.set(DataComponents.CUSTOM_NAME, Component.literal("Materials"));
        inv.setItem(9, box(stone));
        assertEquals(37, ShulkerRefill.take(inv, 9, 0, Items.STONE, new StorageConfig()));
        assertTrue(ItemStack.isSameItemSameComponents(stone, inv.getItem(10)));
        assertEquals(37, inv.getItem(10).getCount());
        assertTrue(contents(inv.getItem(9)).stream().findFirst().orElse(ItemStack.EMPTY).isEmpty());
    }

    @Test void oneItemModeLeavesRemainderInBox() {
        var inv = new SimpleContainer(41);
        inv.setItem(9, box(new ItemStack(Items.STONE, 37)));
        var config = new StorageConfig(); config.refillFullStack = false;
        assertEquals(1, ShulkerRefill.take(inv, 9, 0, Items.STONE, config));
        assertEquals(36, contents(inv.getItem(9)).stream().findFirst().orElse(ItemStack.EMPTY).getCount());
    }

    @Test void fullInventoryMovesBackpackStackIntoVacatedBoxSlotAtomically() {
        var inv = full(); inv.setItem(35, box(new ItemStack(Items.STONE, 64)));
        var hand = inv.getItem(0).copy();
        assertEquals(64, ShulkerRefill.take(inv, 35, 0, Items.STONE, new StorageConfig()));
        assertTrue(inv.getItem(9).is(Items.STONE));
        assertTrue(contents(inv.getItem(35)).stream().findFirst().orElse(ItemStack.EMPTY).is(Items.DIRT));
        assertEquals(64, contents(inv.getItem(35)).stream().findFirst().orElse(ItemStack.EMPTY).getCount());
        assertTrue(ItemStack.matches(hand, inv.getItem(0)));
    }

    @Test void noRoomAndDisabledMakingSpaceLeaveEveryItemUnchanged() {
        var inv = full(); inv.setItem(35, box(new ItemStack(Items.STONE, 64)));
        var config = new StorageConfig(); config.refillMakeSpace = false;
        assertEquals(0, ShulkerRefill.take(inv, 35, 0, Items.STONE, config));
        assertEquals(64, contents(inv.getItem(35)).stream().findFirst().orElse(ItemStack.EMPTY).getCount());
        for (int i = 0; i < 35; i++) assertTrue(inv.getItem(i).is(Items.DIRT));
    }

    @Test void partialExtractionCannotDiscardDisplacedItemsWhenBoxStaysFull() {
        var inv = full(); var stacks = new ItemStack[27];
        java.util.Arrays.setAll(stacks, i -> new ItemStack(Items.STONE, 64));
        inv.setItem(35, box(stacks));
        var config = new StorageConfig(); config.refillFullStack = false;
        assertEquals(0, ShulkerRefill.take(inv, 35, 0, Items.STONE, config));
        assertEquals(1728, contents(inv.getItem(35)).stream().mapToInt(ItemStack::getCount).sum());
    }

    @Test void stackedSourceSplitsExactlyOneBoxAndPreservesRemainder() {
        var inv = full(); inv.setItem(9, ItemStack.EMPTY); inv.setItem(10, ItemStack.EMPTY);
        var boxes = box(new ItemStack(Items.STONE, 12)); inv.setItem(35, boxes); boxes.setCount(3);
        assertEquals(12, ShulkerRefill.take(inv, 35, 0, Items.STONE, new StorageConfig()));
        assertEquals(2, inv.getItem(35).getCount());
        assertEquals(12, contents(inv.getItem(35)).stream().findFirst().orElse(ItemStack.EMPTY).getCount());
        assertTrue(inv.getItem(9).is(Items.STONE));
        assertEquals(1, inv.getItem(10).getCount());
        assertTrue(contents(inv.getItem(10)).stream().allMatch(ItemStack::isEmpty));
    }

    @Test void stackedSourceWithoutSplitSlotIsNeverMutated() {
        var inv = full(); var boxes = box(new ItemStack(Items.STONE, 12)); inv.setItem(35, boxes); boxes.setCount(3);
        assertEquals(0, ShulkerRefill.take(inv, 35, 0, Items.STONE, new StorageConfig()));
        assertEquals(3, inv.getItem(35).getCount());
        assertEquals(12, contents(inv.getItem(35)).stream().findFirst().orElse(ItemStack.EMPTY).getCount());
    }

    @Test void invalidIndicesWrongItemDisabledAndEquipmentSlotsCannotExtract() {
        var inv = full(); inv.setItem(35, box(new ItemStack(Items.STONE, 12))); inv.setItem(36, inv.getItem(35).copy());
        var config = new StorageConfig();
        for (int slot : new int[]{-1, 36, 40, 41}) assertEquals(0, ShulkerRefill.take(inv, slot, 0, Items.STONE, config));
        for (int slot : new int[]{-1, 27, Integer.MAX_VALUE}) assertEquals(0, ShulkerRefill.take(inv, 35, slot, Items.STONE, config));
        assertEquals(0, ShulkerRefill.take(inv, 35, 0, Items.DIAMOND, config));
        config.schematicRefill = false;
        assertEquals(0, ShulkerRefill.take(inv, 35, 0, Items.STONE, config));
        config.schematicRefill = true; config.enabled = false;
        assertEquals(0, ShulkerRefill.take(inv, 35, 0, Items.STONE, config));
        assertEquals(12, contents(inv.getItem(35)).stream().findFirst().orElse(ItemStack.EMPTY).getCount());
    }

    @Test void makingSpaceRespectsMixedItemProtection() {
        var inv = full(); inv.setItem(35, box(new ItemStack(Items.STONE, 12), new ItemStack(Items.COBBLESTONE)));
        var config = new StorageConfig(); config.allowMixedItemsWhenMakingSpace = false;
        assertEquals(0, ShulkerRefill.take(inv, 35, 0, Items.STONE, config));
        assertEquals(13, contents(inv.getItem(35)).stream().mapToInt(ItemStack::getCount).sum());
    }

    private static SimpleContainer full() {
        var inv = new SimpleContainer(41);
        for (int i = 0; i < 36; i++) inv.setItem(i, new ItemStack(Items.DIRT, 64));
        return inv;
    }
    private static ItemStack box(ItemStack... stacks) {
        var box = new ItemStack(Items.SHULKER_BOX);
        box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(stacks)));
        return box;
    }
    private static ItemContainerContents contents(ItemStack box) { return box.get(DataComponents.CONTAINER); }
}
