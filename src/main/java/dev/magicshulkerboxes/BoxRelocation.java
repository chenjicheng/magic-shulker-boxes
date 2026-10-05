package dev.magicshulkerboxes;

import java.util.List;
import java.util.Objects;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

/** Single-type relocation on transaction snapshots. Callers discard the snapshot when planning fails. */
final class BoxRelocation {
    static final long ALL_SLOTS = (1L << 41) - 1;
    record Transfer(ItemStack stack, int destination) {}

    // These payloads define the stored item's type; relaxed matching only ignores other metadata.
    private static final List<DataComponentType<?>> TYPE_COMPONENTS = List.of(
            DataComponents.POTION_CONTENTS,
            DataComponents.POTION_DURATION_SCALE,
            DataComponents.SUSPICIOUS_STEW_EFFECTS,
            DataComponents.STORED_ENCHANTMENTS,
            DataComponents.MAP_ID,
            DataComponents.FIREWORKS,
            DataComponents.FIREWORK_EXPLOSION,
            DataComponents.INSTRUMENT,
            DataComponents.OMINOUS_BOTTLE_AMPLIFIER);

    private BoxRelocation() {}

    static boolean sameType(ItemStack first, ItemStack second, StorageConfig config) {
        if (config.matchItemComponents) return ItemStack.isSameItemSameComponents(first, second);
        if (!ItemStack.isSameItem(first, second)) return false;
        for (var component : TYPE_COMPONENTS) {
            if (!Objects.equals(first.get(component), second.get(component))) return false;
        }
        return true;
    }

    static boolean acceptsType(NonNullList<ItemStack> contents, ItemStack incoming, StorageConfig config) {
        return contents.stream().allMatch(stack -> stack.isEmpty() || sameType(stack, incoming, config));
    }

    static NonNullList<ItemStack> contents(ItemStack box) {
        var stored = box.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
        if (stored.stream().count() > 27) return null;
        var contents = NonNullList.withSize(27, ItemStack.EMPTY);
        stored.copyInto(contents);
        for (int i = 0; i < contents.size(); i++) contents.set(i, contents.get(i).copy());
        return contents;
    }

    static int freeSlot(Container inventory, long eligible) {
        int size = Math.min(Inventory.INVENTORY_SIZE, inventory.getContainerSize());
        for (int offset = 0; offset < size; offset++) {
            int slot = (offset + 9) % size;
            if ((eligible & (1L << slot)) != 0 && inventory.getItem(slot).isEmpty()) return slot;
        }
        return -1;
    }

    static boolean canDisplace(ItemStack stack, int slot, StorageConfig config) {
        return !stack.isEmpty() && !ShulkerStorage.isShulker(stack) && stack.getItem().canFitInsideContainerItems()
                && (slot >= 9 || config.useHotbarForSpace)
                && (config.allowPartialStacksForSpace || stack.getCount() >= stack.getMaxStackSize());
    }

    /** May consolidate several same-type inventory stacks into one new box to obtain a free slot. */
    static int makeSpace(Container inventory, StorageConfig config, long eligible, ItemStack preferred,
                         List<Transfer> transfers) {
        int free = freeSlot(inventory, eligible);
        if (free >= 0) return free;
        int size = Math.min(Inventory.INVENTORY_SIZE, inventory.getContainerSize());
        var rejected = new java.util.ArrayList<ItemStack>();
        for (boolean matching : new boolean[] {true, false}) {
            for (int offset = 0; offset < size; offset++) {
                int slot = (offset + 9) % size;
                var displaced = inventory.getItem(slot);
                if ((eligible & (1L << slot)) == 0 || !canDisplace(displaced, slot, config)
                        || matching != sameType(displaced, preferred, config)) continue;
                // Equivalent failed stacks have the same capacity result until a successful move changes the plan.
                if (rejected.stream().anyMatch(stack -> ItemStack.matches(stack, displaced))) continue;
                int destination = store(inventory, displaced, slot, config, eligible);
                if (destination < 0) { rejected.add(displaced.copy()); continue; }
                rejected.clear();
                if (transfers != null) transfers.add(new Transfer(displaced.copy(), destination));
                if (destination != slot) return slot;
            }
        }
        return -1;
    }

    /** Move the entire stack into a same-type box, then an empty box. Never mix unrelated items. */
    static int store(Container inventory, ItemStack displaced, int vacatedSlot, StorageConfig config, long eligible) {
        var ordered = BoxOrder.fullestFirst(inventory, config);
        for (boolean empty : new boolean[] {false, true}) {
            for (int slot : ordered) {
                if (!allowedBox(inventory, slot, eligible, config)) continue;
                var box = inventory.getItem(slot);
                if (box.getCount() != 1) continue;
                var stored = box.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
                if (stored.stream().count() > 27 || empty != stored.stream().allMatch(ItemStack::isEmpty)
                        || !stored.stream().allMatch(stack -> stack.isEmpty() || sameType(stack, displaced, config))) continue;
                var contents = contents(box);
                if (ShulkerStorage.insert(contents, displaced) != displaced.getCount()) continue;
                var filled = box.copyWithCount(1);
                filled.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(contents));
                CraftingMaterials.write(inventory, slot, filled);
                if (vacatedSlot >= 0 && slot != vacatedSlot) inventory.setItem(vacatedSlot, ItemStack.EMPTY);
                return slot;
            }
        }
        if (!config.splitStackedBoxes) return -1;
        for (int slot : ordered) {
            if (!allowedBox(inventory, slot, eligible, config)) continue;
            var box = inventory.getItem(slot);
            if (box.getCount() <= 1) continue;
            var stored = box.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
            if (stored.stream().count() > 27 || stored.stream().anyMatch(stack -> !stack.isEmpty())) continue;
            var contents = contents(box);
            int destination = freeSlot(inventory, eligible);
            if (destination < 0) destination = vacatedSlot;
            if (destination < 0 || ShulkerStorage.insert(contents, displaced) != displaced.getCount()) continue;
            var filled = box.copyWithCount(1);
            filled.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(contents));
            box.shrink(1);
            CraftingMaterials.write(inventory, destination, filled);
            if (vacatedSlot >= 0 && destination != vacatedSlot) inventory.setItem(vacatedSlot, ItemStack.EMPTY);
            return destination;
        }
        return -1;
    }

    private static boolean allowedBox(Container inventory, int slot, long eligible, StorageConfig config) {
        return (slot < Inventory.INVENTORY_SIZE && (eligible & (1L << slot)) != 0)
                || (slot == Inventory.SLOT_OFFHAND && config.includeOffhand && (eligible & (1L << slot)) != 0)
                || (config.enderChestRefill && RefillSources.isEnder(inventory, slot));
    }
}
