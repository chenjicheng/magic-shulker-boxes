package dev.magicshulkerboxes;

import java.util.function.Predicate;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

/** Copy-based inventory operations shared by recipe placement and continuous crafting. */
final class CraftingMaterials {
    private CraftingMaterials() {}
    record Taken(ItemStack stack) {}

    static Container copy(Container inventory) {
        if (inventory instanceof RefillSources.View view) return new RefillSources.View(copy(view.inventory), copy(view.ender));
        var items = new ItemStack[inventory.getContainerSize()];
        for (int i = 0; i < items.length; i++) items[i] = inventory.getItem(i).copy();
        return new SimpleContainer(items);
    }

    static void write(Container inventory, int slot, ItemStack stack) {
        int count = stack.getCount();
        inventory.setItem(slot, stack.copy());
        // SimpleContainer clamps to vanilla limits; snapshots must also retain Carpet/other overstacked counts.
        if (!stack.isEmpty()) inventory.getItem(slot).setCount(count);
    }

    static void commitChanges(Container original, Container changed, Container target) {
        for (int i = 0; i < changed.getContainerSize(); i++) {
            if (ItemStack.matches(original.getItem(i), changed.getItem(i))) continue;
            var current = target.getItem(i);
            var replacement = changed.getItem(i);
            if (!current.isEmpty() && !replacement.isEmpty() && ItemStack.isSameItemSameComponents(current, replacement)) {
                current.setCount(replacement.getCount());
            } else write(target, i, replacement);
        }
        target.setChanged();
    }

    static boolean putBack(Container inventory, ItemStack remaining) {
        int size = Math.min(36, inventory.getContainerSize());
        for (int pass = 0; pass < 2 && !remaining.isEmpty(); pass++) {
            for (int offset = 0; offset < size && !remaining.isEmpty(); offset++) {
                int slot = (offset + 9) % size;
                var current = inventory.getItem(slot);
                if (pass == 0 && !current.isEmpty() && ItemStack.isSameItemSameComponents(current, remaining)) {
                    int moved = Math.min(remaining.getCount(), current.getMaxStackSize() - current.getCount());
                    if (moved > 0) { current.grow(moved); remaining.shrink(moved); }
                } else if (pass == 1 && current.isEmpty()) {
                    int moved = Math.min(remaining.getCount(), remaining.getMaxStackSize());
                    write(inventory, slot, remaining.copyWithCount(moved)); remaining.shrink(moved);
                }
            }
        }
        return remaining.isEmpty();
    }

    static Taken takeBox(Container inventory, Predicate<ItemStack> matches, int count, StorageConfig config,
                         Predicate<ItemStack> allowedBox) {
        return takeBox(inventory, matches, count, config, allowedBox, () -> {});
    }

    static Taken takeBox(Container inventory, Predicate<ItemStack> matches, int count, StorageConfig config,
                         Predicate<ItemStack> allowedBox, Runnable blockedSpace) {
        var carried = takeBoxPass(inventory, matches, count, config, allowedBox, false, blockedSpace);
        if (carried != null || !config.enderChestRefill || !(inventory instanceof RefillSources.View)) return carried;
        for (int slot = RefillSources.ENDER_START; slot < inventory.getContainerSize(); slot++) {
            var stack = inventory.getItem(slot);
            if (!stack.isEmpty() && !ShulkerStorage.isShulker(stack) && matches.test(stack)) return new Taken(stack.split(Math.min(count, stack.getCount())));
        }
        return takeBoxPass(inventory, matches, count, config, allowedBox, true, blockedSpace);
    }

    private static Taken takeBoxPass(Container inventory, Predicate<ItemStack> matches, int count, StorageConfig config,
                                    Predicate<ItemStack> allowedBox, boolean ender, Runnable blockedSpace) {
        for (int slot : BoxOrder.emptiestFirst(inventory, config)) {
            if (RefillSources.isEnder(inventory, slot) != ender) continue;
            var box = inventory.getItem(slot);
            if (!allowedBox.test(box)) continue;
            var contents = NonNullList.withSize(27, ItemStack.EMPTY);
            box.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).copyInto(contents);
            for (int inner = 0; inner < 27; inner++) {
                var source = contents.get(inner);
                if (source.isEmpty() || !source.getItem().canFitInsideContainerItems() || !matches.test(source)) continue;
                if (box.getCount() > 1 && !config.splitStackedBoxes) { blockedSpace.run(); break; }
                int destination = slot;
                if (box.getCount() > 1) {
                    destination = RefillSources.freeBoxSlot(inventory, slot, config);
                    if (destination < 0) { blockedSpace.run(); break; }
                }
                int amount = Math.min(count, source.getCount());
                var taken = source.copyWithCount(amount);
                contents.set(inner, source.copyWithCount(source.getCount() - amount));
                var single = box.copyWithCount(1);
                single.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(contents));
                if (box.getCount() > 1) box.shrink(1);
                write(inventory, destination, single);
                return new Taken(taken);
            }
        }
        return null;
    }

    static int freeSlot(Container inventory) {
        for (int i = 9; i < Math.min(36, inventory.getContainerSize()); i++) if (inventory.getItem(i).isEmpty()) return i;
        for (int i = 0; i < Math.min(9, inventory.getContainerSize()); i++) if (inventory.getItem(i).isEmpty()) return i;
        return -1;
    }

    static boolean keepRemainder(Container inventory, ItemStack remainder, StorageConfig config) {
        var remaining = remainder.copy();
        if (putBack(inventory, remaining)) return true;
        if (!config.refillMakeSpace || (!config.allowPartialStacksForSpace
                && remaining.getCount() < remaining.getMaxStackSize())) return false;
        if (BoxRelocation.store(inventory, remaining, -1, config, BoxRelocation.ALL_SLOTS) >= 0) return true;
        if (BoxRelocation.makeSpace(inventory, config, BoxRelocation.ALL_SLOTS, remaining, null) < 0) return false;
        return BoxRelocation.store(inventory, remaining, -1, config, BoxRelocation.ALL_SLOTS) >= 0;
    }
}
