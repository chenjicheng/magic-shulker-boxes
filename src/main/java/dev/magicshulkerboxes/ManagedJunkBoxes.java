package dev.magicshulkerboxes;

import java.util.ArrayList;
import java.util.HashSet;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemContainerContents;

/** Roles belong to automatically allocated boxes, and exist only while carried in the player's backpack. */
public final class ManagedJunkBoxes {
    public static final String MARKER = "magic_shulker_boxes:managed_junk";
    public static final int DEDICATED_STACKS = 3;
    private static final ThreadLocal<Inventory> MENU_INVENTORY = new ThreadLocal<>();

    private ManagedJunkBoxes() {}

    public static boolean marked(ItemStack box) {
        return ShulkerStorage.isShulker(box)
                && box.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getBooleanOr(MARKER, false);
    }

    static void mark(ItemStack box) {
        var data = box.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        data.putBoolean(MARKER, true);
        box.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
    }

    public static void clear(ItemStack box) {
        if (!marked(box)) return;
        var data = box.get(DataComponents.CUSTOM_DATA).copyTag();
        data.remove(MARKER);
        if (data.isEmpty()) box.remove(DataComponents.CUSTOM_DATA);
        else box.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
    }

    public static ItemStack outsideCopy(ItemStack box) {
        if (!marked(box)) return box;
        var copy = box.copy();
        clear(copy);
        return copy;
    }

    /** Native menus may remove a stack temporarily while swapping backpack positions. */
    public static void withinMenu(Inventory inventory, Runnable action) {
        var previous = MENU_INVENTORY.get();
        MENU_INVENTORY.set(inventory);
        try { action.run(); }
        finally {
            if (previous == null) MENU_INVENTORY.remove();
            else MENU_INVENTORY.set(previous);
        }
    }

    public static ItemStack removedFrom(Inventory inventory, ItemStack stack) {
        // Removal transfers ownership of this object; clear its aliases too, rather than replacing the native result.
        if (MENU_INVENTORY.get() != inventory) clear(stack);
        return stack;
    }

    private static final class StoredType {
        final ItemStack prototype;
        double stacks;
        StoredType(ItemStack prototype) { this.prototype = prototype; }
    }

    static boolean carriedSlot(int slot) { return slot >= 0 && slot < Inventory.INVENTORY_SIZE; }

    static double storedStacks(Container inventory, ItemStack wanted, StorageConfig config) {
        double count = 0;
        for (int slot = 0; slot < Math.min(Inventory.INVENTORY_SIZE, inventory.getContainerSize()); slot++) {
            var box = inventory.getItem(slot);
            if (!marked(box) || box.getCount() != 1) continue;
            var contents = BoxRelocation.contents(box);
            if (contents == null) continue;
            for (var stack : contents) {
                if (!stack.isEmpty() && BoxRelocation.sameType(stack, wanted, config))
                    count += (double) stack.getCount() / stack.getMaxStackSize();
            }
        }
        return count;
    }

    /** A failed dedicated-box plan cannot partly drain a junk box or change any role. */
    static boolean consolidate(Container inventory, ItemStack wanted, StorageConfig config, long eligible,
                               double receivedThreshold) {
        double stored = storedStacks(inventory, wanted, config);
        if (!config.useMatchingBoxes || stored == 0) return false;
        boolean thresholdReached = Math.max(stored, receivedThreshold) >= DEDICATED_STACKS;
        boolean existingDedicated = BoxOrder.fullestFirst(inventory, config).stream().anyMatch(slot -> {
            var box = inventory.getItem(slot);
            var contents = BoxRelocation.contents(box);
            return BoxRelocation.allowedBox(inventory, slot, eligible, config) && !marked(box) && contents != null
                    && contents.stream().anyMatch(stack -> !stack.isEmpty()) && BoxRelocation.acceptsType(contents, wanted, config);
        });
        if (!thresholdReached && !existingDedicated) return false;
        var original = CraftingMaterials.copy(inventory);
        var planned = CraftingMaterials.copy(inventory);
        var pool = new ArrayList<ItemStack>();
        var promotedSources = new HashSet<Integer>();
        for (int slot = 0; slot < Math.min(Inventory.INVENTORY_SIZE, planned.getContainerSize()); slot++) {
            if ((eligible & (1L << slot)) == 0) continue;
            var box = planned.getItem(slot);
            if (!marked(box) || box.getCount() != 1) continue;
            var contents = BoxRelocation.contents(box);
            if (contents == null) continue;
            boolean removed = false;
            for (int inner = 0; inner < contents.size(); inner++) {
                var stack = contents.get(inner);
                if (!stack.isEmpty() && BoxRelocation.sameType(stack, wanted, config)) {
                    pool.add(stack.copy());
                    contents.set(inner, ItemStack.EMPTY);
                    removed = true;
                }
            }
            if (!removed) continue;
            // A pure source can become the dedicated destination without allocating another box.
            if (thresholdReached && contents.stream().allMatch(ItemStack::isEmpty)) {
                clear(box);
                promotedSources.add(slot);
            }
            box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(contents));
        }
        if (pool.isEmpty()) return false;
        for (boolean emptyPass : new boolean[] {false, true}) {
            if (emptyPass && !thresholdReached) continue;
            for (int slot : BoxOrder.fullestFirst(planned, config)) {
                if (!BoxRelocation.allowedBox(planned, slot, eligible, config)) continue;
                var box = planned.getItem(slot);
                if (marked(box)) continue;
                var contents = BoxRelocation.contents(box);
                if (contents == null || !BoxRelocation.acceptsType(contents, wanted, config)) continue;
                boolean empty = contents.stream().allMatch(ItemStack::isEmpty);
                if (empty != emptyPass || (empty && !config.useEmptyBoxes && !promotedSources.contains(slot))) continue;
                do {
                    int destination = slot;
                    if (box.getCount() > 1) {
                        if (!empty || !config.splitStackedBoxes) break;
                        destination = BoxRelocation.freeSlot(planned, HotbarProtection.unprotectedSlots(eligible, config));
                        if (destination < 0) break;
                    }
                    var filled = box.copyWithCount(1);
                    var updated = BoxRelocation.contents(filled);
                    for (var stack : pool) {
                        if (!stack.isEmpty()) stack.shrink(ShulkerStorage.insert(updated, stack));
                    }
                    filled.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(updated));
                    if (destination != slot) box.shrink(1);
                    CraftingMaterials.write(planned, destination, filled);
                    if (pool.stream().allMatch(ItemStack::isEmpty)) {
                        CraftingMaterials.commitChanges(original, planned, inventory);
                        return true;
                    }
                    if (destination == slot) break;
                } while (box.getCount() > 1);
            }
        }
        return false;
    }

    /** Run after a transaction's real transfers finish, never during a DROP reservation. */
    static void consolidateStored(Container inventory, StorageConfig config, long eligible) {
        consolidateStored(inventory, config, eligible, null);
    }

    static void consolidateStored(Container inventory, StorageConfig config, long eligible, ItemStack alreadyConsidered) {
        var types = new ArrayList<StoredType>();
        for (int slot = 0; slot < Math.min(Inventory.INVENTORY_SIZE, inventory.getContainerSize()); slot++) {
            if ((eligible & (1L << slot)) == 0 || !marked(inventory.getItem(slot))) continue;
            var contents = BoxRelocation.contents(inventory.getItem(slot));
            if (contents == null) continue;
            for (var stack : contents) {
                if (stack.isEmpty() || (alreadyConsidered != null && BoxRelocation.sameType(stack, alreadyConsidered, config))) continue;
                var type = types.stream().filter(value -> BoxRelocation.sameType(value.prototype, stack, config)).findFirst().orElse(null);
                if (type == null) { type = new StoredType(stack.copyWithCount(1)); types.add(type); }
                type.stacks += (double) stack.getCount() / stack.getMaxStackSize();
            }
        }
        // Sparse unrelated types cannot meet the creation threshold; avoid cloning an inventory once per type.
        for (var type : types) if (type.stacks >= DEDICATED_STACKS) consolidate(inventory, type.prototype, config, eligible, 0);
    }
}
