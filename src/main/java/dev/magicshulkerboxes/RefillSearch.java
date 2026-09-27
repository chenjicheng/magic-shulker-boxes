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
        for (int slot : BoxOrder.emptiestFirst(inventory, config)) {
            var box = inventory.getItem(slot);
            if (!ShulkerStorage.isShulker(box)) continue;
            var stored = box.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
            if (stored.stream().count() > 27) continue;
            stored.copyInto(contents);
            for (int i = 0; i < 27; i++) {
                if (ItemStack.isSameItemSameComponents(contents.get(i), wanted)) return new Match(slot, i);
            }
        }
        return null;
    }
}
