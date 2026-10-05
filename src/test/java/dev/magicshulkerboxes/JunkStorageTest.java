package dev.magicshulkerboxes;

import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JunkStorageTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @Test void explicitSlotAcceptsOtherTypesAndMovingTheBoxRemovesTheRole() {
        var inventory = new SimpleContainer(36);
        var marked = box(new ItemStack(Items.DIRT, 3));
        inventory.setItem(9, marked);
        var config = config(9);
        var incoming = new ItemStack(Items.STONE, 5);
        assertEquals(5, ShulkerStorage.store(inventory, incoming, config));
        assertEquals(8, count(inventory.getItem(9)));
        inventory.setItem(10, inventory.getItem(9));
        inventory.setItem(9, ItemStack.EMPTY);
        var more = new ItemStack(Items.GRAVEL, 7);
        assertEquals(0, ShulkerStorage.store(inventory, more, config));
        assertEquals(7, more.getCount(), "Role belongs to slot 9, not the box moved to slot 10");
    }

    @Test void dedicatedMatchingBoxWinsAndMarkedSingleTypeBoxDoesNotBecomeDedicated() {
        var inventory = new SimpleContainer(36);
        inventory.setItem(9, box(new ItemStack(Items.STONE, 3)));
        inventory.setItem(10, box(new ItemStack(Items.STONE, 6)));
        var config = config(9);
        assertEquals(2, ShulkerStorage.storeMatching(inventory, new ItemStack(Items.STONE, 2), config));
        assertEquals(3, count(inventory.getItem(9)));
        assertEquals(8, count(inventory.getItem(10)));
        inventory.setItem(10, ItemStack.EMPTY);
        assertEquals(0, ShulkerStorage.storeMatching(inventory, new ItemStack(Items.STONE, 2), config));
    }

    @Test void markedMixedBoxPrecedesUnmarkedEmptyBox() {
        var inventory = new SimpleContainer(36);
        inventory.setItem(9, box());
        inventory.setItem(10, box(new ItemStack(Items.DIRT), new ItemStack(Items.GRAVEL)));
        assertEquals(4, ShulkerStorage.store(inventory, new ItemStack(Items.STONE, 4), config(10)));
        assertEquals(0, count(inventory.getItem(9)));
        assertEquals(6, count(inventory.getItem(10)));
    }

    @Test void relocationUsesJunkBeforeCreatingANewDedicatedBoxAndHonorsTheEligibleMask() {
        var inventory = new SimpleContainer(36);
        inventory.setItem(9, box());
        inventory.setItem(10, box(new ItemStack(Items.GRAVEL)));
        inventory.setItem(11, new ItemStack(Items.STONE, 12));
        var config = config(10);
        assertEquals(10, BoxRelocation.store(inventory, inventory.getItem(11), 11, config, BoxRelocation.ALL_SLOTS));
        assertTrue(inventory.getItem(11).isEmpty());
        assertEquals(13, count(inventory.getItem(10)));
        inventory.setItem(12, new ItemStack(Items.DIRT, 4));
        assertEquals(9, BoxRelocation.store(inventory, inventory.getItem(12), 12, config,
                BoxRelocation.ALL_SLOTS & ~(1L << 10)));
        assertEquals(13, count(inventory.getItem(10)), "An excluded IPN slot is not a junk destination");
    }

    @Test void emptyStackSplittingKeepsFilledBoxInTheDesignatedSlot() {
        var inventory = new SimpleContainer(36);
        inventory.setItem(9, box());
        inventory.getItem(9).setCount(16);
        assertEquals(16, inventory.getItem(9).getCount(), "The fixture contains sixteen actual empty boxes");
        assertEquals(5, ShulkerStorage.store(inventory, new ItemStack(Items.STONE, 5), config(9)));
        assertEquals(1, inventory.getItem(9).getCount());
        assertEquals(5, count(inventory.getItem(9)));
        int totalBoxes = 0;
        for (int slot = 0; slot < 36; slot++) if (ShulkerStorage.isShulker(inventory.getItem(slot))) totalBoxes += inventory.getItem(slot).getCount();
        assertEquals(16, totalBoxes);
    }

    @Test void designatedSlotsAreNotEvictedOrUsedForAutomaticSplitDestinations() {
        var inventory = new SimpleContainer(36);
        for (int slot = 0; slot < 36; slot++) inventory.setItem(slot, new ItemStack(Items.DIRT, 64));
        inventory.setItem(9, box());
        var config = config(10);
        assertFalse(BoxRelocation.canDisplace(inventory.getItem(10), 10, config));
        inventory.setItem(10, ItemStack.EMPTY);
        inventory.setItem(9, box()); inventory.getItem(9).setCount(16);
        config.makeSpaceMode = StorageConfig.MakeSpaceMode.DISABLED;
        assertEquals(0, ShulkerStorage.store(inventory, new ItemStack(Items.STONE, 5), config));
        assertTrue(inventory.getItem(10).isEmpty());
        assertEquals(16, inventory.getItem(9).getCount());
    }

    @Test void refillingKeepsAnEmptyDesignatedSlotAvailableForThePlayersBox() {
        var inventory = new SimpleContainer(36);
        for (int slot = 0; slot < 36; slot++) inventory.setItem(slot, new ItemStack(Items.DIRT, 64));
        inventory.setItem(9, ItemStack.EMPTY); inventory.setItem(11, ItemStack.EMPTY);
        inventory.setItem(10, box(new ItemStack(Items.STONE, 5)));
        assertEquals(5, ShulkerRefill.take(inventory, 10, 0, Items.STONE, config(9)));
        assertTrue(inventory.getItem(9).isEmpty(), "Refill does not consume a designated empty slot");
        assertTrue(inventory.getItem(11).is(Items.STONE));
    }

    @Test void junkBoxesCannotReceiveNestedShulkerBoxes() {
        var inventory = new SimpleContainer(36); inventory.setItem(9, box());
        assertEquals(-1, BoxRelocation.store(inventory, box(), -1, config(9), BoxRelocation.ALL_SLOTS));
        assertEquals(0, count(inventory.getItem(9)));
    }

    private static StorageConfig config(int slot) {
        var config = new StorageConfig(); config.pickupStorageEnabled = true;
        config.junkBoxSlots = 1L << slot; return config;
    }
    private static ItemStack box(ItemStack... contents) {
        var stack = new ItemStack(Items.SHULKER_BOX);
        stack.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(contents))); return stack;
    }
    private static int count(ItemStack box) {
        return box.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).stream().mapToInt(ItemStack::getCount).sum();
    }
}
