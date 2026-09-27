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

class ShulkerStorageTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void fillsNearlyFullMatchingBoxBeforeEarlierSparseBox() {
        var inventory = fullInventory();
        inventory.setItem(0, box(new ItemStack(Items.STONE)));
        var packed = new ItemStack[27];
        java.util.Arrays.setAll(packed, i -> new ItemStack(Items.STONE, i == 26 ? 60 : 64));
        inventory.setItem(35, box(packed));
        assertEquals(5, ShulkerStorage.store(inventory, new ItemStack(Items.STONE, 5), new StorageConfig()));
        assertEquals(1728, countContents(inventory.getItem(35)), "Fill the nearly full box first");
        assertEquals(2, countContents(inventory.getItem(0)), "Only the remainder enters the sparse box");
    }

    @Test
    void mixedBoxFullnessUsesStackLimitsAndSkipsIncompatibleFullBoxes() {
        var inventory = fullInventory();
        inventory.setItem(0, box(new ItemStack(Items.STONE, 64), new ItemStack(Items.DIRT, 64)));
        var packed = new ItemStack[27];
        java.util.Arrays.setAll(packed, i -> new ItemStack(Items.DIAMOND_PICKAXE));
        packed[26] = new ItemStack(Items.STONE);
        inventory.setItem(35, box(packed));
        // An even fuller mixed box cannot accept any stone and must be skipped.
        packed[26] = new ItemStack(Items.ENDER_PEARL, 16);
        inventory.setItem(34, box(packed));
        assertEquals(3, ShulkerStorage.store(inventory, new ItemStack(Items.STONE, 3), new StorageConfig()));
        assertEquals(30, countContents(inventory.getItem(35)));
        assertEquals(128, countContents(inventory.getItem(0)));
        assertEquals(42, countContents(inventory.getItem(34)));
    }

    @Test
    void sixteenStackItemsContributeTheirOwnStackLimitToFullness() {
        var inventory = fullInventory();
        inventory.setItem(0, box(new ItemStack(Items.STONE, 64), new ItemStack(Items.DIRT)));
        inventory.setItem(35, box(new ItemStack(Items.ENDER_PEARL, 16), new ItemStack(Items.STONE, 32)));
        assertEquals(1, ShulkerStorage.store(inventory, new ItemStack(Items.STONE), new StorageConfig()));
        assertEquals(49, countContents(inventory.getItem(35)), "1.5 occupied stacks precede 1 + 1/64 stacks");
        assertEquals(65, countContents(inventory.getItem(0)));
    }

    @Test
    void matchingBoxWinsEvenWhenEmptyAndMixedBoxesHaveEarlierSlots() {
        var inventory = fullInventory();
        inventory.setItem(0, box());
        inventory.setItem(1, box(new ItemStack(Items.DIRT), new ItemStack(Items.STONE)));
        inventory.setItem(35, box(new ItemStack(Items.COBBLESTONE, 63)));
        var incoming = new ItemStack(Items.COBBLESTONE, 3);

        assertEquals(3, ShulkerStorage.store(inventory, incoming, new StorageConfig()));
        assertTrue(incoming.isEmpty());
        assertEquals(66, countContents(inventory.getItem(35)));
        assertEquals(0, countContents(inventory.getItem(0)));
        assertEquals(2, countContents(inventory.getItem(1)));
    }

    @Test
    void overflowUsesEmptyBoxBeforeMixedBox() {
        var inventory = fullInventory();
        var nearlyFull = new ItemStack[27];
        for (int i = 0; i < 27; i++) nearlyFull[i] = new ItemStack(Items.COBBLESTONE, 64);
        nearlyFull[26].setCount(63);
        inventory.setItem(0, box(new ItemStack(Items.DIRT), new ItemStack(Items.STONE)));
        inventory.setItem(1, box());
        inventory.setItem(2, box(nearlyFull));
        var incoming = new ItemStack(Items.COBBLESTONE, 10);

        assertEquals(10, ShulkerStorage.store(inventory, incoming, new StorageConfig()));
        assertEquals(1728, countContents(inventory.getItem(2)));
        assertEquals(9, countContents(inventory.getItem(1)));
        assertEquals(2, countContents(inventory.getItem(0)));
    }

    @Test
    void stackedEmptyBoxWithoutFreeSlotFallsBackToMixedWithoutChangingStack() {
        var inventory = fullInventory();
        var stacked = box();
        inventory.setItem(0, stacked);
        stacked.setCount(16);
        inventory.setItem(1, box(new ItemStack(Items.DIRT), new ItemStack(Items.STONE)));
        var incoming = new ItemStack(Items.COBBLESTONE, 5);
        var config = new StorageConfig();
        config.makeSpaceMode = StorageConfig.MakeSpaceMode.DISABLED;

        assertEquals(5, ShulkerStorage.store(inventory, incoming, config));
        assertEquals(16, stacked.getCount());
        assertEquals(0, countContents(stacked));
        assertEquals(7, countContents(inventory.getItem(1)));
    }

    @Test
    void splitsExactlyOneStackedBoxAndPreservesColorNameAndRemainder() {
        var inventory = fullInventory();
        var stacked = new ItemStack(Items.BLUE_SHULKER_BOX, 16);
        stacked.set(DataComponents.CUSTOM_NAME, Component.literal("建材"));
        inventory.setItem(0, stacked);
        stacked.setCount(16);
        inventory.setItem(4, ItemStack.EMPTY);
        var incoming = new ItemStack(Items.COBBLESTONE, 5);

        assertEquals(5, ShulkerStorage.store(inventory, incoming, new StorageConfig()));
        assertEquals(15, stacked.getCount());
        assertEquals(0, countContents(stacked));
        assertEquals(1, inventory.getItem(4).getCount());
        assertTrue(inventory.getItem(4).is(Items.BLUE_SHULKER_BOX));
        assertEquals("建材", inventory.getItem(4).getHoverName().getString());
        assertEquals(5, countContents(inventory.getItem(4)));
    }

    @Test
    void otherSingleTypeBoxIsOptInLastResort() {
        var inventory = fullInventory();
        inventory.setItem(0, box(new ItemStack(Items.DIRT)));
        var incoming = new ItemStack(Items.COBBLESTONE, 5);
        var config = new StorageConfig();

        assertEquals(0, ShulkerStorage.store(inventory, incoming, config));
        assertEquals(5, incoming.getCount());
        config.allowOtherSingleTypeBoxes = true;
        assertEquals(5, ShulkerStorage.store(inventory, incoming, config));
        assertEquals(6, countContents(inventory.getItem(0)));
    }

    @Test
    void partiallyAcceptsWithoutDeletingLeftovers() {
        var inventory = fullInventory();
        var contents = new ItemStack[27];
        for (int i = 0; i < 27; i++) contents[i] = new ItemStack(Items.COBBLESTONE, 64);
        contents[26].setCount(61);
        inventory.setItem(0, box(contents));
        var incoming = new ItemStack(Items.COBBLESTONE, 10);

        assertEquals(3, ShulkerStorage.store(inventory, incoming, new StorageConfig()));
        assertEquals(7, incoming.getCount());
        assertEquals(1728, countContents(inventory.getItem(0)));
    }

    @Test
    void neverNestsShulkerBoxes() {
        var inventory = fullInventory();
        inventory.setItem(0, box());
        var incoming = new ItemStack(Items.RED_SHULKER_BOX);
        assertEquals(0, ShulkerStorage.store(inventory, incoming, new StorageConfig()));
        assertEquals(1, incoming.getCount());
    }

    @Test
    void itemComponentsArePreservedAndNeverMergedIntoDifferentComponents() {
        var inventory = fullInventory();
        inventory.setItem(0, box(new ItemStack(Items.COBBLESTONE, 63)));
        var incoming = new ItemStack(Items.COBBLESTONE, 5);
        incoming.set(DataComponents.CUSTOM_NAME, Component.literal("特殊圆石"));
        assertEquals(5, ShulkerStorage.store(inventory, incoming, new StorageConfig()));
        var contents = inventory.getItem(0).get(DataComponents.CONTAINER).stream().toList();
        assertEquals(63, contents.get(0).getCount());
        assertEquals("特殊圆石", contents.get(1).getHoverName().getString());
        assertEquals(5, contents.get(1).getCount());
    }

    @Test
    void fullBoxDoesNotSplitOrConsumeAnEmptySlot() {
        var inventory = fullInventory();
        var contents = new ItemStack[27];
        for (int i = 0; i < 27; i++) contents[i] = new ItemStack(Items.COBBLESTONE, 64);
        var stacked = box(contents);
        inventory.setItem(0, stacked);
        stacked.setCount(2);
        inventory.setItem(1, ItemStack.EMPTY);
        var incoming = new ItemStack(Items.COBBLESTONE, 5);
        assertEquals(0, ShulkerStorage.store(inventory, incoming, new StorageConfig()));
        assertEquals(2, stacked.getCount());
        assertTrue(inventory.getItem(1).isEmpty());
        assertEquals(5, incoming.getCount());
    }

    @Test
    void enabledAndCategorySwitchesAreRespected() {
        var inventory = fullInventory();
        inventory.setItem(0, box(new ItemStack(Items.COBBLESTONE)));
        inventory.setItem(1, box());
        inventory.setItem(2, box(new ItemStack(Items.DIRT), new ItemStack(Items.STONE)));
        var incoming = new ItemStack(Items.COBBLESTONE, 5);
        var config = new StorageConfig();
        config.enabled = false;
        assertEquals(0, ShulkerStorage.store(inventory, incoming, config));
        config.enabled = true;
        config.useMatchingBoxes = false;
        config.useEmptyBoxes = false;
        config.useMixedBoxes = false;
        config.allowOtherSingleTypeBoxes = true;
        assertEquals(0, ShulkerStorage.store(inventory, incoming, config));
        config.useMixedBoxes = true;
        assertEquals(5, ShulkerStorage.store(inventory, incoming, config));
        assertEquals(7, countContents(inventory.getItem(2)));
    }

    @Test
    void disablingSplittingLeavesStackUntouchedEvenWithFreeSlot() {
        var inventory = fullInventory();
        var stacked = box();
        inventory.setItem(0, stacked);
        stacked.setCount(16);
        inventory.setItem(1, ItemStack.EMPTY);
        var config = new StorageConfig();
        config.splitStackedBoxes = false;
        assertEquals(0, ShulkerStorage.store(inventory, new ItemStack(Items.COBBLESTONE, 5), config));
        assertEquals(16, stacked.getCount());
        assertTrue(inventory.getItem(1).isEmpty());
    }

    @Test
    void offhandIsOptInAndArmorSlotsNeverBecomeStorageOrSplitDestinations() {
        var inventory = fullInventory();
        inventory.setItem(36, box());
        inventory.setItem(40, box());
        var incoming = new ItemStack(Items.COBBLESTONE, 5);
        var config = new StorageConfig();
        assertEquals(0, ShulkerStorage.store(inventory, incoming, config));
        config.includeOffhand = true;
        assertEquals(5, ShulkerStorage.store(inventory, incoming, config));
        assertEquals(0, countContents(inventory.getItem(36)));
        assertEquals(5, countContents(inventory.getItem(40)));
    }

    @Test
    void strictTypeMatchingDistinguishesComponentsButDefaultUsesItemId() {
        var inventory = fullInventory();
        inventory.setItem(0, box(new ItemStack(Items.COBBLESTONE)));
        var incoming = new ItemStack(Items.COBBLESTONE, 5);
        incoming.set(DataComponents.CUSTOM_NAME, Component.literal("named"));
        var config = new StorageConfig();
        config.matchItemComponents = true;
        assertEquals(0, ShulkerStorage.store(inventory, incoming, config));
        config.matchItemComponents = false;
        assertEquals(5, ShulkerStorage.store(inventory, incoming, config));
    }

    @Test
    void respectsSixteenItemAndUnstackableLimits() {
        var inventory = fullInventory();
        inventory.setItem(0, box(new ItemStack(Items.ENDER_PEARL, 15)));
        assertEquals(3, ShulkerStorage.store(inventory, new ItemStack(Items.ENDER_PEARL, 3), new StorageConfig()));
        var contents = inventory.getItem(0).get(DataComponents.CONTAINER).stream().toList();
        assertEquals(16, contents.get(0).getCount());
        assertEquals(2, contents.get(1).getCount());
        inventory.setItem(1, box());
        assertEquals(2, ShulkerStorage.store(inventory, new ItemStack(Items.DIAMOND_PICKAXE, 2), new StorageConfig()));
        contents = inventory.getItem(1).get(DataComponents.CONTAINER).stream().toList();
        assertEquals(1, contents.get(0).getCount());
        assertEquals(1, contents.get(1).getCount());
    }

    @Test
    void neverTruncatesNonstandardLargeContainers() {
        var inventory = fullInventory();
        var contents = new ItemStack[28];
        for (int i = 0; i < 28; i++) contents[i] = new ItemStack(Items.COBBLESTONE);
        inventory.setItem(0, box(contents));
        var incoming = new ItemStack(Items.COBBLESTONE, 5);
        assertEquals(0, ShulkerStorage.store(inventory, incoming, new StorageConfig()));
        assertEquals(28, countContents(inventory.getItem(0)));
    }

    @Test
    void defaultMakesSpaceWithoutDroppingOrChangingTotalItems() {
        var inventory = fullInventory();
        var stacked = box();
        inventory.setItem(0, stacked);
        stacked.setCount(16);
        var named = new ItemStack(Items.STONE, 64);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("Keep this name"));
        inventory.setItem(9, named);
        var incoming = new ItemStack(Items.COBBLESTONE, 5);

        assertEquals(5, ShulkerStorage.store(inventory, incoming, new StorageConfig()));
        assertEquals(15, stacked.getCount());
        assertEquals(0, countContents(stacked));
        assertEquals(1, inventory.getItem(9).getCount());
        var contents = inventory.getItem(9).get(DataComponents.CONTAINER).stream().toList();
        assertEquals(69, countContents(inventory.getItem(9)));
        assertEquals(64, contents.get(0).getCount());
        assertEquals("Keep this name", contents.get(0).getHoverName().getString());
        assertEquals(5, contents.get(1).getCount());
        assertEquals(64, inventory.getItem(1).getCount(), "Hotbar was not moved");
    }

    @Test
    void makesSpaceWithSameTypeBeforeAnotherEarlierInventoryStack() {
        var inventory = fullInventory();
        var stacked = box();
        inventory.setItem(0, stacked);
        stacked.setCount(16);
        inventory.setItem(20, new ItemStack(Items.COBBLESTONE, 64));
        assertEquals(5, ShulkerStorage.store(inventory, new ItemStack(Items.COBBLESTONE, 5), new StorageConfig()));
        assertEquals(69, countContents(inventory.getItem(20)));
        assertTrue(inventory.getItem(9).is(Items.STONE));
    }

    @Test
    void hotbarRelocationIsOptInAndBoxesAreNeverRelocationCandidates() {
        var inventory = fullInventory();
        for (int i = 9; i < 36; i++) inventory.setItem(i, box());
        var stacked = inventory.getItem(9);
        stacked.setCount(16);
        // Disable empty singleton boxes as usable destinations by filling them completely.
        var full = new ItemStack[27];
        for (int i = 0; i < 27; i++) full[i] = new ItemStack(Items.STONE, 64);
        for (int i = 10; i < 36; i++) inventory.setItem(i, box(full));
        var incoming = new ItemStack(Items.COBBLESTONE, 5);
        var config = new StorageConfig();
        assertEquals(0, ShulkerStorage.store(inventory, incoming, config));
        assertEquals(16, stacked.getCount());
        config.useHotbarForSpace = true;
        assertEquals(5, ShulkerStorage.store(inventory, incoming, config));
        assertEquals(69, countContents(inventory.getItem(0)));
        assertEquals(15, stacked.getCount());
    }

    @Test
    void cannotMoveAnEntireStackOrStoreIncomingLeavesEverythingUntouched() {
        var inventory = fullInventory();
        var full = new ItemStack[27];
        for (int i = 0; i < 27; i++) full[i] = new ItemStack(Items.COBBLESTONE, 64);
        full[26].setCount(1);
        var stacked = box(full);
        inventory.setItem(0, stacked);
        stacked.setCount(2);
        inventory.setItem(9, new ItemStack(Items.COBBLESTONE, 64));
        var incoming = new ItemStack(Items.COBBLESTONE, 5);
        assertEquals(0, ShulkerStorage.store(inventory, incoming, new StorageConfig()));
        assertEquals(2, stacked.getCount());
        assertEquals(1665, countContents(stacked));
        assertEquals(64, inventory.getItem(9).getCount());
        assertEquals(5, incoming.getCount());
    }

    @Test
    void reservesWholeRelocatedStackBeforeAcceptingPartialPickup() {
        var inventory = fullInventory();
        var contents = new ItemStack[27];
        for (int i = 0; i < 27; i++) contents[i] = new ItemStack(Items.COBBLESTONE, 64);
        contents[26].setCount(1);
        var stacked = box(contents);
        inventory.setItem(0, stacked);
        stacked.setCount(2);
        inventory.setItem(9, new ItemStack(Items.COBBLESTONE, 60));
        var incoming = new ItemStack(Items.COBBLESTONE, 5);
        assertEquals(3, ShulkerStorage.store(inventory, incoming, new StorageConfig()));
        assertEquals(2, incoming.getCount());
        assertEquals(1, stacked.getCount());
        assertEquals(1665, countContents(stacked));
        assertEquals(1728, countContents(inventory.getItem(9)));
    }

    @Test
    void autoSpaceNeverMixesAnUnrelatedItemIntoMatchingBox() {
        var inventory = fullInventory();
        var stacked = box(new ItemStack(Items.COBBLESTONE));
        inventory.setItem(0, stacked);
        stacked.setCount(2);
        assertEquals(0, ShulkerStorage.store(inventory, new ItemStack(Items.COBBLESTONE, 5), new StorageConfig()));
        assertEquals(2, stacked.getCount());
        assertEquals(1, countContents(stacked));
    }

    @Test
    void partialStacksAndMixingHaveIndependentSwitches() {
        var inventory = fullInventory();
        for (int i = 0; i < 36; i++) inventory.setItem(i, box());
        var stacked = inventory.getItem(0);
        stacked.setCount(16);
        // Full boxes cannot be nested or used for this incoming type.
        for (int i = 1; i < 36; i++) inventory.setItem(i, box(new ItemStack(Items.DIRT)));
        inventory.setItem(9, new ItemStack(Items.STONE, 3));
        var incoming = new ItemStack(Items.COBBLESTONE, 5);
        var config = new StorageConfig();
        config.allowPartialStacksForSpace = false;
        assertEquals(0, ShulkerStorage.store(inventory, incoming, config));
        config.allowPartialStacksForSpace = true;
        config.allowMixedItemsWhenMakingSpace = false;
        assertEquals(0, ShulkerStorage.store(inventory, incoming, config));
        config.allowMixedItemsWhenMakingSpace = true;
        assertEquals(5, ShulkerStorage.store(inventory, incoming, config));
        assertEquals(8, countContents(inventory.getItem(9)));
        assertEquals(15, stacked.getCount());
        // The resulting multi-kind box is reused; repeated junk pickups need no new box.
        assertEquals(7, ShulkerStorage.store(inventory, new ItemStack(Items.GRAVEL, 7), config));
        assertEquals(15, countContents(inventory.getItem(9)));
        assertEquals(15, stacked.getCount());
    }

    @Test
    void existingBoxPreferenceCanBeDisabledToMakeSpaceInCategoryOrder() {
        var inventory = fullInventory();
        var stacked = box();
        inventory.setItem(0, stacked);
        stacked.setCount(16);
        inventory.setItem(1, box(new ItemStack(Items.DIRT), new ItemStack(Items.STONE)));
        var config = new StorageConfig();
        assertEquals(5, ShulkerStorage.store(inventory, new ItemStack(Items.COBBLESTONE, 5), config));
        assertEquals(16, stacked.getCount());
        config.preferExistingBoxesBeforeMakingSpace = false;
        assertEquals(5, ShulkerStorage.store(inventory, new ItemStack(Items.COBBLESTONE, 5), config));
        assertEquals(15, stacked.getCount());
        assertEquals(69, countContents(inventory.getItem(9)));
        assertEquals(7, countContents(inventory.getItem(1)));
    }

    @Test
    void rejectedDropLeavesInventoryAndPickupUnchanged() {
        var inventory = fullInventory();
        var stacked = box();
        inventory.setItem(0, stacked);
        stacked.setCount(16);
        var config = new StorageConfig();
        config.makeSpaceMode = StorageConfig.MakeSpaceMode.DROP_AND_PICKUP;
        var incoming = new ItemStack(Items.COBBLESTONE, 5);
        int[] attempts = {0};
        assertEquals(0, ShulkerStorage.store(inventory, incoming, config, true, (displaced, slot, filled, commit) -> {
            attempts[0]++;
            // Simulate the world rejecting creation: do not commit any inventory changes.
        }));
        assertEquals(1, attempts[0]);
        assertEquals(16, stacked.getCount());
        assertEquals(64, inventory.getItem(9).getCount());
        assertEquals(5, incoming.getCount());
    }

    @Test
    void reservedDropCannotWriteIntoAReplacedBox() {
        var inventory = fullInventory();
        var stacked = box();
        inventory.setItem(0, stacked);
        stacked.setCount(16);
        inventory.setItem(9, new ItemStack(Items.STONE, 3));
        var config = new StorageConfig();
        config.makeSpaceMode = StorageConfig.MakeSpaceMode.DROP_AND_PICKUP;
        var incoming = new ItemStack(Items.COBBLESTONE, 5);
        assertEquals(5, ShulkerStorage.store(inventory, incoming, config, true, (displaced, slot, filled, commit) -> {
            assertEquals(5, countContents(filled), "Displaced stack is not duplicated before repickup");
            commit.run();
            inventory.setItem(slot, box());
            assertEquals(0, ShulkerStorage.collectRelocated(inventory, slot, filled, displaced));
            assertEquals(3, displaced.getCount(), "Rejected relocation stays intact as a world item");
        }));
        assertEquals(15, stacked.getCount());
        assertEquals(0, countContents(inventory.getItem(9)));
    }

    static SimpleContainer fullInventory() {
        var inventory = new SimpleContainer(41);
        for (int i = 0; i < 36; i++) inventory.setItem(i, new ItemStack(Items.STONE, 64));
        return inventory;
    }

    static ItemStack box(ItemStack... contents) {
        var box = new ItemStack(Items.SHULKER_BOX);
        box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(contents)));
        return box;
    }

    static int countContents(ItemStack box) {
        return box.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY)
                .stream().mapToInt(ItemStack::getCount).sum();
    }
}
