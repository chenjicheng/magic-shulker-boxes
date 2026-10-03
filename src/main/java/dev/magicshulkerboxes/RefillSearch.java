package dev.magicshulkerboxes;

import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.ItemContainerContents;

/** Read-only material lookup; calling it never reserves slots or changes item counts. */
public final class RefillSearch {
    private RefillSearch() {}
    public record Match(int boxSlot, int contentSlot) {}
    public static Match find(Container inventory, ItemStack wanted, StorageConfig config) {
        if (wanted.isEmpty()) return null;
        var contents = NonNullList.withSize(27, ItemStack.EMPTY);
        var slots = BoxOrder.emptiestFirst(inventory, config);
        for (int slot : slots) {
            if (RefillSources.isEnder(inventory, slot)) continue;
            var match = findBox(inventory, wanted, slot, contents);
            if (match != null) return match;
        }
        if (!config.enderChestRefill || !(inventory instanceof RefillSources.View)) return null;
        for (int slot = RefillSources.ENDER_START; slot < inventory.getContainerSize(); slot++) {
            if (!ShulkerStorage.isShulker(inventory.getItem(slot)) && ItemStack.isSameItemSameComponents(inventory.getItem(slot), wanted)) return new Match(slot, -1);
        }
        for (int slot : slots) {
            if (!RefillSources.isEnder(inventory, slot)) continue;
            var match = findBox(inventory, wanted, slot, contents);
            if (match != null) return match;
        }
        return null;
    }
    private static Match findBox(Container inventory, ItemStack wanted, int slot, NonNullList<ItemStack> contents) {
        var box = inventory.getItem(slot);
        if (!ShulkerStorage.isShulker(box)) return null;
        var stored = box.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
        if (stored.stream().count() > 27) return null;
        stored.copyInto(contents);
        for (int i = 0; i < 27; i++) {
            if (ItemStack.isSameItemSameComponents(contents.get(i), wanted)) return new Match(slot, i);
        }
        return null;
    }
}
