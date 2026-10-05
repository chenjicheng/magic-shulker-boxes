package dev.magicshulkerboxes;

import java.util.List;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

/** Refill a server-observed crafting pattern only when every missing ingredient and remainder can be handled. */
public final class CraftingRefill {
    private CraftingRefill() {}

    public static boolean refill(Container inventory, Container grid, List<ItemStack> template,
                                 List<ItemStack> remainders, StorageConfig config) {
        return refill(inventory, grid, template, remainders, config, () -> {});
    }

    /** Reports a capacity failure once; missing materials and changed input patterns remain silent. */
    public static boolean refill(Container inventory, Container grid, List<ItemStack> template,
                                 List<ItemStack> remainders, StorageConfig config, Runnable blockedSpace) {
        if (!config.craftRefill || grid.getContainerSize() < 1 || grid.getContainerSize() > 9
                || template.size() != grid.getContainerSize() || remainders.size() != template.size()) return false;
        var originalInventory = CraftingMaterials.copy(inventory);
        var plannedInventory = CraftingMaterials.copy(inventory);
        var originalGrid = CraftingMaterials.copy(grid);
        var plannedGrid = CraftingMaterials.copy(grid);
        boolean changed = false;
        for (int cell = 0; cell < template.size(); cell++) {
            var wanted = template.get(cell); var current = plannedGrid.getItem(cell);
            if (wanted.isEmpty()) { if (!current.isEmpty()) return false; continue; }
            if (!current.isEmpty() && ItemStack.isSameItemSameComponents(wanted, current)) continue;
            if (!current.isEmpty() && !ItemStack.matches(current, remainders.get(cell))) return false;
            ItemStack replacement = ItemStack.EMPTY;
            for (int slot = 0; slot < Math.min(36, plannedInventory.getContainerSize()); slot++) {
                var source = plannedInventory.getItem(slot);
                if (!source.isEmpty() && ItemStack.isSameItemSameComponents(source, wanted)) {
                    replacement = source.split(1); break;
                }
            }
            if (replacement.isEmpty()) {
                boolean[] spaceBlocked = {false};
                var taken = CraftingMaterials.takeBox(plannedInventory, stack -> ItemStack.isSameItemSameComponents(stack, wanted),
                        1, config, box -> template.stream().noneMatch(input -> !input.isEmpty() && input.is(box.getItem())),
                        () -> spaceBlocked[0] = true);
                if (taken == null) {
                    if (spaceBlocked[0]) blockedSpace.run();
                    return false;
                }
                replacement = taken.stack();
            }
            if (!current.isEmpty() && !CraftingMaterials.keepRemainder(plannedInventory, current, config)) {
                blockedSpace.run(); return false;
            }
            CraftingMaterials.write(plannedGrid, cell, replacement);
            changed = true;
        }
        if (!changed) return false;
        CraftingMaterials.commitChanges(originalInventory, plannedInventory, inventory);
        CraftingMaterials.commitChanges(originalGrid, plannedGrid, grid);
        return true;
    }
}
