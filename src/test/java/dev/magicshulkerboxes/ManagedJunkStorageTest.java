package dev.magicshulkerboxes;

import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemContainerContents;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ManagedJunkStorageTest {
    private static final String MARKER = "magic_shulker_boxes:managed_junk";

    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @Test void smallReceiptsCreateOneManagedBoxFromRealEmptyStock() {
        var inventory = new SimpleContainer(36);
        inventory.setItem(9, box());
        inventory.getItem(9).setCount(16);
        assertEquals(5, ShulkerStorage.store(inventory, new ItemStack(Items.STONE, 5), config()));
        assertEquals(16, boxes(inventory));
        int managed = 0;
        for (int slot = 0; slot < 36; slot++) {
            var item = inventory.getItem(slot);
            if (marked(item)) {
                managed++;
                assertEquals(1, item.getCount(), "Only the detached single box acquires a role");
                assertEquals(5, count(item, Items.STONE));
            }
        }
        assertEquals(1, managed);
        assertFalse(marked(inventory.getItem(9)), "The remaining empty stock has no role");
    }

    @Test void onlyManagedMixedBoxesCanReceiveNewTypes() {
        var inventory = new SimpleContainer(36);
        var original = box(new ItemStack(Items.DIRT), new ItemStack(Items.GRAVEL));
        inventory.setItem(9, original.copy());
        inventory.setItem(10, managed(new ItemStack(Items.SAND)));
        assertEquals(7, ShulkerStorage.store(inventory, new ItemStack(Items.STONE, 7), config()));
        assertTrue(ItemStack.matches(original, inventory.getItem(9)));
        assertEquals(7, count(inventory.getItem(10), Items.STONE));
    }

    @Test void movingAManagedBoxInsideInventoryPreservesItsRole() {
        var inventory = new SimpleContainer(36);
        inventory.setItem(9, managed(new ItemStack(Items.DIRT)));
        inventory.setItem(18, inventory.removeItemNoUpdate(9));
        assertEquals(2, ShulkerStorage.store(inventory, new ItemStack(Items.GRAVEL, 2), config()));
        assertTrue(marked(inventory.getItem(18)));
    }

    @Test void threeStacksIncludeAllMatchingStockInManagedBoxes() {
        var inventory = new SimpleContainer(36);
        inventory.setItem(9, managed(new ItemStack(Items.STONE, 64), new ItemStack(Items.DIRT, 2)));
        inventory.setItem(10, managed(new ItemStack(Items.STONE, 64), new ItemStack(Items.GRAVEL, 3)));
        inventory.setItem(11, box());
        assertEquals(64, ShulkerStorage.store(inventory, new ItemStack(Items.STONE, 64), config()));
        assertEquals(0, count(inventory.getItem(9), Items.STONE));
        assertEquals(0, count(inventory.getItem(10), Items.STONE));
        assertEquals(192, count(inventory.getItem(11), Items.STONE));
        assertFalse(marked(inventory.getItem(11)));
        assertEquals(2, count(inventory.getItem(9), Items.DIRT));
        assertEquals(3, count(inventory.getItem(10), Items.GRAVEL));
    }

    @Test void fewerThanThreeStacksRemainInManagedStorage() {
        var inventory = new SimpleContainer(36);
        inventory.setItem(9, managed(new ItemStack(Items.STONE, 64), new ItemStack(Items.DIRT)));
        inventory.setItem(10, box());
        assertEquals(64, ShulkerStorage.store(inventory, new ItemStack(Items.STONE, 64), config()));
        assertEquals(128, count(inventory.getItem(9), Items.STONE));
        assertEquals(0, count(inventory.getItem(10), Items.STONE));
    }

    @Test void aPureManagedBoxIsPromotedWithoutAnotherBoxOrFreeSlot() {
        var inventory = new SimpleContainer(36);
        inventory.setItem(9, managed(new ItemStack(Items.STONE, 64), new ItemStack(Items.STONE, 64)));
        assertEquals(64, ShulkerStorage.store(inventory, new ItemStack(Items.STONE, 64), config()));
        assertEquals(192, count(inventory.getItem(9), Items.STONE));
        assertFalse(marked(inventory.getItem(9)), "A promoted dedicated box stops receiving junk");
        assertEquals(0, ShulkerStorage.store(inventory, new ItemStack(Items.DIRT), config()));
    }

    @Test void sixteenStackItemsPromoteAtFortyEightAndUnstackablesAtThree() {
        for (var item : List.of(Items.ENDER_PEARL, Items.IRON_SWORD)) {
            var inventory = new SimpleContainer(36);
            int max = new ItemStack(item).getMaxStackSize();
            inventory.setItem(9, managed(new ItemStack(item, max), new ItemStack(item, max), new ItemStack(Items.DIRT)));
            inventory.setItem(10, box());
            assertEquals(max, ShulkerStorage.store(inventory, new ItemStack(item, max), config()));
            assertEquals(3 * max, count(inventory.getItem(10), item));
            assertEquals(0, count(inventory.getItem(9), item));
        }
    }

    @Test void aFailedPromotionLeavesExistingContentsAndRolesIntact() {
        var inventory = new SimpleContainer(36);
        inventory.setItem(9, managed(new ItemStack(Items.STONE, 64), new ItemStack(Items.STONE, 64), new ItemStack(Items.DIRT)));
        var original = inventory.getItem(9).copy();
        assertEquals(64, ShulkerStorage.store(inventory, new ItemStack(Items.STONE, 64), config()));
        assertTrue(marked(inventory.getItem(9)));
        assertEquals(192, count(inventory.getItem(9), Items.STONE));
        assertEquals(count(original, Items.DIRT), count(inventory.getItem(9), Items.DIRT));
    }

    @Test void existingDedicatedBoxesAlsoCollectOldManagedFragments() {
        var inventory = new SimpleContainer(36);
        inventory.setItem(9, box(new ItemStack(Items.STONE, 64)));
        inventory.setItem(10, managed(new ItemStack(Items.STONE, 5), new ItemStack(Items.DIRT, 3)));
        assertEquals(1, ShulkerStorage.store(inventory, new ItemStack(Items.STONE), config()));
        assertEquals(70, count(inventory.getItem(9), Items.STONE));
        assertEquals(0, count(inventory.getItem(10), Items.STONE));
        assertEquals(3, count(inventory.getItem(10), Items.DIRT));
    }

    @Test void aProtectedEmptyHotbarPositionCannotReceiveASplitDedicatedBox() {
        var inventory = new SimpleContainer(36);
        for (int slot = 1; slot < 36; slot++) inventory.setItem(slot, box(new ItemStack(Items.DIAMOND)));
        inventory.setItem(9, box()); inventory.getItem(9).setCount(16);
        inventory.setItem(10, managed(new ItemStack(Items.STONE, 64), new ItemStack(Items.STONE, 64), new ItemStack(Items.DIRT)));
        var config = config(); config.useHotbarForSpace = true; config.protectHotbarSlot1 = true;
        assertEquals(64, ShulkerStorage.store(inventory, new ItemStack(Items.STONE, 64), config));
        assertTrue(inventory.getItem(0).isEmpty());
        assertEquals(16, inventory.getItem(9).getCount());
        assertEquals(192, count(inventory.getItem(10), Items.STONE));
        assertTrue(marked(inventory.getItem(10)), "A failed complete promotion keeps managed storage intact");
    }

    @Test void aPureBoxCanPromoteWithoutPermissionToConsumeEmptyStock() {
        var inventory = new SimpleContainer(36);
        inventory.setItem(9, managed(new ItemStack(Items.STONE, 64), new ItemStack(Items.STONE, 64)));
        var config = config(); config.useEmptyBoxes = false;
        assertEquals(64, ShulkerStorage.store(inventory, new ItemStack(Items.STONE, 64), config));
        assertFalse(marked(inventory.getItem(9)));
        assertEquals(192, count(inventory.getItem(9), Items.STONE));
    }

    static StorageConfig config() {
        var config = new StorageConfig();
        config.pickupStorageEnabled = true;
        config.makeSpaceMode = StorageConfig.MakeSpaceMode.DISABLED;
        return config;
    }

    static ItemStack box(ItemStack... items) {
        var box = new ItemStack(Items.SHULKER_BOX);
        box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(items)));
        return box;
    }

    static ItemStack managed(ItemStack... items) {
        var box = box(items);
        var tag = new CompoundTag();
        tag.putBoolean(MARKER, true);
        box.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return box;
    }

    static boolean marked(ItemStack box) {
        return box.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getBooleanOr(MARKER, false);
    }

    static int count(ItemStack box, net.minecraft.world.item.Item item) {
        return box.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).stream()
                .filter(stack -> stack.is(item)).mapToInt(ItemStack::getCount).sum();
    }

    static int boxes(net.minecraft.world.Container inventory) {
        int count = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++)
            if (ShulkerStorage.isShulker(inventory.getItem(slot))) count += inventory.getItem(slot).getCount();
        return count;
    }
}
