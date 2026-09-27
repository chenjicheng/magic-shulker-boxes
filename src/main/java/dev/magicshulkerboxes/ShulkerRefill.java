package dev.magicshulkerboxes;

import net.minecraft.world.Container;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

/** Server-side material extraction, independent of the optional schematic client. */
public final class ShulkerRefill {
    private ShulkerRefill() {}
    public static int take(Container inventory, int boxSlot, int contentSlot, Item expected, StorageConfig config) {
        int size = Math.min(36, inventory.getContainerSize());
        boolean offhand = config.includeOffhand && boxSlot == Inventory.SLOT_OFFHAND && boxSlot < inventory.getContainerSize();
        if (!config.schematicRefill || boxSlot < 0 || (boxSlot >= size && !offhand)
                || contentSlot < 0 || contentSlot >= 27) return 0;
        var box = inventory.getItem(boxSlot);
        if (!ShulkerStorage.isShulker(box) || (box.getCount() > 1 && !config.splitStackedBoxes)) return 0;
        var container = box.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
        if (container.stream().count() > 27) return 0;
        var original = NonNullList.withSize(27, ItemStack.EMPTY);
        container.copyInto(original);
        var source = original.get(contentSlot);
        if (source.isEmpty() || !source.is(expected) || !source.getItem().canFitInsideContainerItems()) return 0;
        int count = Math.min(source.getCount(), config.refillFullStack ? source.getMaxStackSize() : 1);

        // Prefer an empty backpack slot. Only try relocation when no free destination works.
        for (int pass = 0; pass < 2; pass++) {
            if (pass == 1 && !config.refillMakeSpace) break;
            for (int offset = 0; offset < size; offset++) {
                int destination = (offset + 9) % size;
                if (destination == boxSlot) continue;
                var displaced = inventory.getItem(destination);
                if ((pass == 0) != displaced.isEmpty()) continue;
                if (!displaced.isEmpty() && (ShulkerStorage.isShulker(displaced) || !displaced.getItem().canFitInsideContainerItems()
                        || (destination < 9 && !config.useHotbarForSpace)
                        || (!config.allowPartialStacksForSpace && displaced.getCount() < displaced.getMaxStackSize()))) continue;
                int splitSlot = boxSlot;
                if (box.getCount() > 1) {
                    splitSlot = -1;
                    for (int slot = 0; slot < size; slot++) {
                        if (slot != destination && slot != boxSlot && inventory.getItem(slot).isEmpty()) { splitSlot = slot; break; }
                    }
                    if (splitSlot == -1) continue;
                }
                var contents = NonNullList.withSize(27, ItemStack.EMPTY);
                for (int i = 0; i < 27; i++) contents.set(i, original.get(i).copy());
                contents.get(contentSlot).shrink(count);
                if (!displaced.isEmpty() && !config.allowMixedItemsWhenMakingSpace && contents.stream().anyMatch(stack ->
                        !stack.isEmpty() && !(config.matchItemComponents ? ItemStack.isSameItemSameComponents(stack, displaced)
                                : ItemStack.isSameItem(stack, displaced)))) continue;
                if (!displaced.isEmpty() && ShulkerStorage.insert(contents, displaced) != displaced.getCount()) continue;

                // Plan against copies first; neither failure nor a stacked source may partially mutate inventory.
                var changedBox = box.copyWithCount(1);
                changedBox.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(contents));
                if (box.getCount() > 1) box.shrink(1);
                inventory.setItem(splitSlot, changedBox);
                inventory.setItem(destination, source.copyWithCount(count));
                inventory.setChanged();
                return count;
            }
        }
        return 0;
    }
}
