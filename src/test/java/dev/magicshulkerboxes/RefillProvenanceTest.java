package dev.magicshulkerboxes;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RefillProvenanceTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-00000000c0de");

    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
    @BeforeEach @AfterEach void reset() { RefillProvenance.clearAll(); }

    @Test void returnsTheMarkedStackToItsOriginalBoxWithoutTouchingItemData() {
        var inventory = new SimpleContainer(41);
        inventory.setItem(0, new ItemStack(Items.STONE, 12));
        inventory.setItem(35, box());
        RefillProvenance.record(inventory, PLAYER, 0, inventory.getItem(0), 12, 35, false, true);
        assertTrue(inventory.getItem(0).getComponentsPatch().isEmpty(), "Recording never adds item components");

        var plan = RefillProvenance.planReturn(inventory, PLAYER, 0, inventory.getItem(0), new StorageConfig());
        assertNotNull(plan);
        assertEquals(12, plan.returned());
        assertEquals(12, count(inventory.getItem(35), Items.STONE));
        assertEquals(1, RefillProvenance.view(PLAYER).size());

        plan.completion().run();
        plan.completion().run();
        assertTrue(RefillProvenance.view(PLAYER).isEmpty(), "A plan cannot be applied twice");
    }

    @Test void aFullSourceReturnsOnlyWhatStillFitsAndLeavesTheRestForNormalStorage() {
        var inventory = new SimpleContainer(41);
        inventory.setItem(0, new ItemStack(Items.STONE, 12));
        var contents = new ArrayList<ItemStack>();
        for (int i = 0; i < 26; i++) contents.add(new ItemStack(Items.STONE, 64));
        contents.add(new ItemStack(Items.STONE, 58));
        inventory.setItem(35, box(contents));
        RefillProvenance.record(inventory, PLAYER, 0, inventory.getItem(0), 12, 35, false, true);

        var plan = RefillProvenance.planReturn(inventory, PLAYER, 0, inventory.getItem(0), new StorageConfig());
        assertNotNull(plan);
        assertEquals(6, plan.returned(), "Only the six free positions return to the source");
        assertEquals(1728, count(inventory.getItem(35), Items.STONE));
    }

    @Test void returningToAnExistingBoxStillWorksWhenEmptyBoxesAreDisabled() {
        var inventory = new SimpleContainer(41);
        inventory.setItem(0, new ItemStack(Items.STONE, 12));
        inventory.setItem(35, box());
        RefillProvenance.record(inventory, PLAYER, 0, inventory.getItem(0), 12, 35, false, true);
        var config = new StorageConfig();
        config.useEmptyBoxes = false;
        var plan = RefillProvenance.planReturn(inventory, PLAYER, 0, inventory.getItem(0), config);
        assertNotNull(plan);
        assertEquals(12, plan.returned(), "The original box is not a newly allocated empty box");
    }

    @Test void aMovedOrReplacedSourceClearsTheEntryWithoutReturning() {
        var inventory = new SimpleContainer(41);
        inventory.setItem(0, new ItemStack(Items.STONE, 12));
        inventory.setItem(35, box());
        RefillProvenance.record(inventory, PLAYER, 0, inventory.getItem(0), 12, 35, false, true);
        inventory.setItem(35, new ItemStack(Items.DIRT, 64));

        var plan = RefillProvenance.planReturn(inventory, PLAYER, 0, inventory.getItem(0), new StorageConfig());
        assertNotNull(plan);
        assertEquals(0, plan.returned());
        plan.completion().run();
        assertTrue(RefillProvenance.view(PLAYER).isEmpty());
    }

    @Test void reconciliationMigratesAUniqueHotbarStackAndCapsItsCount() {
        var inventory = new SimpleContainer(41);
        inventory.setItem(0, new ItemStack(Items.STONE, 12));
        inventory.setItem(35, box());
        RefillProvenance.record(inventory, PLAYER, 0, inventory.getItem(0), 12, 35, false, true);
        inventory.setItem(0, ItemStack.EMPTY);
        inventory.setItem(3, new ItemStack(Items.STONE, 5));

        assertTrue(RefillProvenance.reconcile(inventory, PLAYER));
        var entries = RefillProvenance.view(PLAYER);
        assertEquals(1, entries.size());
        assertEquals(3, entries.getFirst().anchorSlot());
        assertEquals(5, entries.getFirst().remaining());
    }

    @Test void reconciliationFollowsTheStackAcrossTheInventoryAndDropsAmbiguousCopies() {
        var inventory = new SimpleContainer(41);
        inventory.setItem(0, new ItemStack(Items.STONE, 12));
        inventory.setItem(35, box());
        RefillProvenance.record(inventory, PLAYER, 0, inventory.getItem(0), 12, 35, false, true);

        inventory.setItem(0, ItemStack.EMPTY);
        inventory.setItem(9, new ItemStack(Items.STONE, 12));
        assertTrue(RefillProvenance.reconcile(inventory, PLAYER));
        var moved = RefillProvenance.view(PLAYER);
        assertEquals(1, moved.size());
        assertEquals(9, moved.getFirst().anchorSlot(), "The main inventory still marks the stack");

        inventory.setItem(9, ItemStack.EMPTY);
        inventory.setItem(40, new ItemStack(Items.STONE, 12));
        assertTrue(RefillProvenance.reconcile(inventory, PLAYER));
        assertTrue(RefillProvenance.view(PLAYER).isEmpty(), "Offhand is outside the marked inventory area");

        RefillProvenance.clearAll();
        inventory.setItem(40, ItemStack.EMPTY);
        inventory.setItem(1, new ItemStack(Items.STONE, 12));
        inventory.setItem(2, new ItemStack(Items.STONE, 12));
        RefillProvenance.record(inventory, PLAYER, 1, inventory.getItem(1), 12, 35, false, true);
        inventory.setItem(1, ItemStack.EMPTY);
        inventory.setItem(3, new ItemStack(Items.STONE, 12));
        assertTrue(RefillProvenance.reconcile(inventory, PLAYER));
        assertTrue(RefillProvenance.view(PLAYER).isEmpty(), "Two candidate stacks are ambiguous");
    }

    @Test void reconciliationMovesTheAnchorBackFromTheInventoryToTheHotbar() {
        var inventory = new SimpleContainer(41);
        inventory.setItem(0, new ItemStack(Items.STONE, 12));
        inventory.setItem(35, box());
        RefillProvenance.record(inventory, PLAYER, 0, inventory.getItem(0), 12, 35, false, true);
        inventory.setItem(0, ItemStack.EMPTY);
        inventory.setItem(9, new ItemStack(Items.STONE, 12));
        RefillProvenance.reconcile(inventory, PLAYER);
        assertEquals(9, RefillProvenance.view(PLAYER).getFirst().anchorSlot());

        inventory.setItem(9, ItemStack.EMPTY);
        inventory.setItem(3, new ItemStack(Items.STONE, 7));
        assertTrue(RefillProvenance.reconcile(inventory, PLAYER));
        var entry = RefillProvenance.view(PLAYER).getFirst();
        assertEquals(3, entry.anchorSlot());
        assertEquals(7, entry.remaining());
    }

    @Test void directEnderSourceReturnsToItsOwnSlot() {
        var main = new SimpleContainer(41);
        var ender = new SimpleContainer(27);
        var sources = new RefillSources.View(main, ender);
        main.setItem(0, new ItemStack(Items.STONE, 5));
        RefillProvenance.record(sources, PLAYER, 0, main.getItem(0), 5, 100, true, false);

        var config = new StorageConfig();
        config.enderChestRefill = true;
        var plan = RefillProvenance.planReturn(sources, PLAYER, 0, main.getItem(0), config);
        assertNotNull(plan);
        assertEquals(5, plan.returned());
        assertEquals(5, ender.getItem(0).getCount());
    }

    @Test void returningRespectsTheCurrentEnderAndOffhandSwitches() {
        var main = new SimpleContainer(41);
        var ender = new SimpleContainer(27);
        var sources = new RefillSources.View(main, ender);
        main.setItem(0, new ItemStack(Items.STONE, 5));
        RefillProvenance.record(sources, PLAYER, 0, main.getItem(0), 5, 100, true, false);
        var disabled = new StorageConfig();
        assertEquals(0, RefillProvenance.planReturn(sources, PLAYER, 0, main.getItem(0), disabled).returned(),
                "A disabled ender source is not written");
        RefillProvenance.clearAll();

        var inventory = new SimpleContainer(41);
        inventory.setItem(0, new ItemStack(Items.STONE, 5));
        inventory.setItem(40, box());
        RefillProvenance.record(inventory, PLAYER, 0, inventory.getItem(0), 5, 40, false, true);
        assertEquals(0, RefillProvenance.planReturn(inventory, PLAYER, 0, inventory.getItem(0), disabled).returned(),
                "A disabled offhand source is not written");
        disabled.includeOffhand = true;
        assertEquals(5, RefillProvenance.planReturn(inventory, PLAYER, 0, inventory.getItem(0), disabled).returned());
    }

    @Test void clearRemovesEveryEntry() {
        var inventory = new SimpleContainer(41);
        inventory.setItem(0, new ItemStack(Items.STONE, 12));
        RefillProvenance.record(inventory, PLAYER, 0, inventory.getItem(0), 12, 35, false, true);
        RefillProvenance.clear(PLAYER);
        assertTrue(RefillProvenance.view(PLAYER).isEmpty());
    }

    private static ItemStack box(ItemStack... contents) { return box(List.of(contents)); }

    private static ItemStack box(List<ItemStack> contents) {
        var box = new ItemStack(Items.SHULKER_BOX);
        box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(contents));
        return box;
    }

    private static int count(ItemStack box, net.minecraft.world.item.Item item) {
        return box.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).stream()
                .filter(stack -> stack.is(item)).mapToInt(ItemStack::getCount).sum();
    }
}
