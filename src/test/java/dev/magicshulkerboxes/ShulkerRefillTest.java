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
        assertTrue(ItemStack.isSameItemSameComponents(stone, inv.getItem(0)));
        assertEquals(37, inv.getItem(0).getCount());
        assertTrue(contents(inv.getItem(9)).stream().findFirst().orElse(ItemStack.EMPTY).isEmpty());
    }

    @Test void schematicRefillUsesAnEmptyHotbarSlotInsteadOfTheBackpack() {
        var inv = new SimpleContainer(41);
        inv.setItem(9, box(new ItemStack(Items.STONE, 12)));
        assertEquals(12, ShulkerRefill.take(inv, 9, 0, Items.STONE, new StorageConfig()));
        assertTrue(inv.getItem(0).is(Items.STONE), "Refilled material goes to the first empty hotbar slot");
        assertEquals(12, inv.getItem(0).getCount());
        assertTrue(inv.getItem(10).isEmpty(), "The backpack does not receive the refill");
    }

    @Test void replacingAFullHotbarSlotStoresTheOldStackInAManagedJunkBox() {
        var inv = new SimpleContainer(41);
        for (int i = 0; i < 36; i++) inv.setItem(i, new ItemStack(Items.DIRT, 64));
        var junk = box();
        ManagedJunkBoxes.mark(junk);
        inv.setItem(10, junk);
        inv.setItem(35, box(new ItemStack(Items.STONE, 12)));
        assertEquals(12, ShulkerRefill.take(inv, 35, 0, Items.STONE, new StorageConfig()));
        assertTrue(inv.getItem(0).is(Items.STONE), "The selected hotbar stack is replaced by the material");
        assertEquals(64, count(inv.getItem(10), Items.DIRT), "The replaced stack is stored in the managed junk box");
        assertTrue(ManagedJunkBoxes.marked(inv.getItem(10)), "Reusing an empty box for junk keeps its role");
        assertEquals(0, count(inv.getItem(35), Items.STONE), "The emptied source box remains available for storage");
    }

    @Test void replacingAFullHotbarSlotPromotesTheJunkTypeAtThreeStacks() {
        var inv = new SimpleContainer(41);
        for (int i = 0; i < 36; i++) inv.setItem(i, new ItemStack(Items.DIRT, 64));
        var junk = box(new ItemStack(Items.DIRT, 64), new ItemStack(Items.DIRT, 64));
        ManagedJunkBoxes.mark(junk);
        inv.setItem(10, junk);
        inv.setItem(35, box(new ItemStack(Items.STONE, 12)));
        assertEquals(12, ShulkerRefill.take(inv, 35, 0, Items.STONE, new StorageConfig()));
        assertTrue(inv.getItem(0).is(Items.STONE), "The selected hotbar stack is replaced by the material");
        assertEquals(192, count(inv.getItem(10), Items.DIRT), "Three stacks promote to a dedicated box");
        assertFalse(ManagedJunkBoxes.marked(inv.getItem(10)), "A promoted pure source box gives up its junk role");
        assertTrue(contents(inv.getItem(35)).stream().allMatch(ItemStack::isEmpty),
                "The emptied source box is no longer needed as the dedicated destination");
    }

    @Test void replacingAFullHotbarSlotSkipsDamageableItems() {
        var inv = new SimpleContainer(41);
        for (int i = 0; i < 36; i++) inv.setItem(i, new ItemStack(Items.DIRT, 64));
        inv.setItem(0, new ItemStack(Items.DIAMOND_PICKAXE));
        inv.setItem(35, box(new ItemStack(Items.STONE, 12)));
        assertEquals(12, ShulkerRefill.take(inv, 35, 0, Items.STONE, new StorageConfig()));
        assertTrue(inv.getItem(0).is(Items.DIAMOND_PICKAXE), "A tool is never displaced by schematic refill");
        assertTrue(inv.getItem(1).is(Items.STONE), "The next replaceable hotbar slot receives the material");
    }

    @Test void disablingEmptyBoxesStopsReusingTheEmptiedSourceBox() {
        var inv = full(); inv.setItem(35, box(new ItemStack(Items.STONE, 12)));
        var config = new StorageConfig(); config.useEmptyBoxes = false;
        assertEquals(0, ShulkerRefill.take(inv, 35, 0, Items.STONE, config),
                "Without empty boxes and backpack space the replacement is refused");
        assertEquals(12, count(inv.getItem(35), Items.STONE), "The source box keeps its materials");
        assertTrue(inv.getItem(0).is(Items.DIRT), "The hotbar stack is unchanged");
    }

    @Test void existingManagedJunkBoxStillReceivesWhenEmptyBoxesAreDisabled() {
        var inv = new SimpleContainer(41);
        for (int i = 0; i < 36; i++) inv.setItem(i, new ItemStack(Items.DIRT, 64));
        var junk = box();
        ManagedJunkBoxes.mark(junk);
        inv.setItem(10, junk);
        inv.setItem(35, box(new ItemStack(Items.STONE, 12)));
        var config = new StorageConfig(); config.useEmptyBoxes = false;
        assertEquals(12, ShulkerRefill.take(inv, 35, 0, Items.STONE, config));
        assertTrue(inv.getItem(0).is(Items.STONE));
        assertEquals(64, count(inv.getItem(10), Items.DIRT), "An existing managed box remains usable");
    }

    @Test void fullHotbarWithoutMakingSpaceKeepsTheBackpackFallback() {
        var inv = new SimpleContainer(41);
        for (int i = 0; i < 36; i++) inv.setItem(i, new ItemStack(Items.DIRT, 64));
        inv.setItem(34, ItemStack.EMPTY);
        inv.setItem(35, box(new ItemStack(Items.STONE, 12)));
        var config = new StorageConfig();
        config.refillMakeSpace = false;
        assertEquals(12, ShulkerRefill.take(inv, 35, 0, Items.STONE, config));
        assertTrue(inv.getItem(34).is(Items.STONE), "Without making space the refill keeps its backpack fallback");
        assertTrue(inv.getItem(0).is(Items.DIRT), "The hotbar stack is not replaced when making space is disabled");
    }

    @Test void oneItemModeLeavesRemainderInBox() {
        var inv = new SimpleContainer(41);
        inv.setItem(9, box(new ItemStack(Items.STONE, 37)));
        var config = new StorageConfig(); config.refillAmount = 1;
        assertEquals(1, ShulkerRefill.take(inv, 9, 0, Items.STONE, config));
        assertEquals(36, contents(inv.getItem(9)).stream().findFirst().orElse(ItemStack.EMPTY).getCount());
    }

    @Test void fullInventoryReplacesTheFirstHotbarStackAndStoresItInTheVacatedBox() {
        var inv = full(); inv.setItem(35, box(new ItemStack(Items.STONE, 64)));
        var hand = inv.getItem(0).copy();
        assertEquals(64, ShulkerRefill.take(inv, 35, 0, Items.STONE, new StorageConfig()));
        assertTrue(inv.getItem(0).is(Items.STONE), "The replaced hotbar stack leaves without touching the backpack");
        assertTrue(contents(inv.getItem(35)).stream().findFirst().orElse(ItemStack.EMPTY).is(Items.DIRT));
        assertEquals(64, contents(inv.getItem(35)).stream().findFirst().orElse(ItemStack.EMPTY).getCount());
        assertTrue(ManagedJunkBoxes.marked(inv.getItem(35)),
                "The emptied source box becomes managed storage for the replaced stack");
        assertFalse(ItemStack.matches(hand, inv.getItem(0)));
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
        var config = new StorageConfig(); config.refillAmount = 1;
        assertEquals(0, ShulkerRefill.take(inv, 35, 0, Items.STONE, config));
        assertEquals(1728, contents(inv.getItem(35)).stream().mapToInt(ItemStack::getCount).sum());
    }

    @Test void stackedSourceSplitsExactlyOneBoxAndPreservesRemainder() {
        var inv = full(); inv.setItem(9, ItemStack.EMPTY); inv.setItem(10, ItemStack.EMPTY);
        var boxes = box(new ItemStack(Items.STONE, 12)); inv.setItem(35, boxes); boxes.setCount(3);
        assertEquals(12, ShulkerRefill.take(inv, 35, 0, Items.STONE, new StorageConfig()));
        assertEquals(2, inv.getItem(35).getCount());
        assertEquals(12, contents(inv.getItem(35)).stream().findFirst().orElse(ItemStack.EMPTY).getCount());
        assertTrue(inv.getItem(0).is(Items.STONE), "The material replaces the first hotbar stack");
        assertEquals(1, inv.getItem(9).getCount());
        assertEquals(64, count(inv.getItem(9), Items.DIRT),
                "The split empty box receives the replaced stack as managed storage");
        assertTrue(ManagedJunkBoxes.marked(inv.getItem(9)));
        assertTrue(inv.getItem(10).isEmpty(), "No second backpack slot is consumed");
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
        assertEquals(12, contents(inv.getItem(35)).stream().findFirst().orElse(ItemStack.EMPTY).getCount());
    }

    @Test void pickupAndRefillSwitchesControlOnlyTheirOwnDirection() {
        for (boolean pickup : new boolean[]{false, true}) {
            for (boolean refill : new boolean[]{false, true}) {
                var config = new StorageConfig();
                config.pickupStorageEnabled = pickup;
                config.schematicRefill = refill;
                var receiving = full();
                receiving.setItem(35, box());
                var dropped = new ItemStack(Items.STONE, 5);
                assertEquals(pickup ? 5 : 0, ShulkerStorage.store(receiving, dropped, config));
                assertEquals(pickup ? 0 : 5, dropped.getCount());
                var supplying = new SimpleContainer(41);
                supplying.setItem(9, box(new ItemStack(Items.STONE, 12)));
                assertEquals(refill ? 12 : 0, ShulkerRefill.take(supplying, 9, 0, Items.STONE, config),
                        "Refill depends only on its own switch; pickup=" + pickup);
                assertEquals(refill ? 0 : 12, contents(supplying.getItem(9)).stream().mapToInt(ItemStack::getCount).sum());
            }
        }
    }

    @Test void makingSpaceNeverMixesUnrelatedItems() {
        var inv = full(); inv.setItem(35, box(new ItemStack(Items.STONE, 12), new ItemStack(Items.COBBLESTONE)));
        var config = new StorageConfig();
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
    private static int count(ItemStack box, net.minecraft.world.item.Item item) {
        return box.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).stream()
                .filter(stack -> stack.is(item)).mapToInt(ItemStack::getCount).sum();
    }
}
