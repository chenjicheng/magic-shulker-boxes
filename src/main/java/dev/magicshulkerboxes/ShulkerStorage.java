package dev.magicshulkerboxes;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.Container;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.ShulkerBoxBlock;

public final class ShulkerStorage {
    private ShulkerStorage() {}

    /** Stores only the supplied remainder; the caller controls when vanilla pickup runs. */
    public static int store(Container inventory, ItemStack incoming, StorageConfig config) {
        return store(inventory, incoming, config, true, null);
    }

    /** Matching boxes precede vanilla inventory; new boxes follow the player's empty-box preference. */
    public static int storeMatching(Container inventory, ItemStack incoming, StorageConfig config) {
        if (!config.pickupStorageEnabled || incoming.isEmpty() || isShulker(incoming)
                || !incoming.getItem().canFitInsideContainerItems()) return 0;
        int originalCount = incoming.getCount();
        storePass(inventory, incoming, config, false, null, true);
        return originalCount - incoming.getCount();
    }

    public static int store(Container inventory, ItemStack incoming, StorageConfig config,
                            boolean allowMakingSpace, RelocationHandler dropHandler) {
        if (!config.pickupStorageEnabled || incoming.isEmpty() || !incoming.getItem().canFitInsideContainerItems()
                || isShulker(incoming)) {
            return 0;
        }

        int originalCount = incoming.getCount();
        boolean makeSpace = allowMakingSpace && config.makeSpaceMode != StorageConfig.MakeSpaceMode.DISABLED;
        storePass(inventory, incoming, config, makeSpace && !config.preferExistingBoxesBeforeMakingSpace, dropHandler);
        if (makeSpace && config.preferExistingBoxesBeforeMakingSpace && !incoming.isEmpty()) {
            storePass(inventory, incoming, config, true, dropHandler);
        }
        return originalCount - incoming.getCount();
    }

    private static void storePass(Container inventory, ItemStack incoming, StorageConfig config,
                                  boolean makeSpace, RelocationHandler dropHandler) {
        storePass(inventory, incoming, config, makeSpace, dropHandler, false);
    }

    private static void storePass(Container inventory, ItemStack incoming, StorageConfig config,
                                  boolean makeSpace, RelocationHandler dropHandler, boolean matchingOnly) {
        var orderedSlots = BoxOrder.fullestFirst(inventory, config);
        for (BoxKind kind : BoxKind.values()) {
            if (matchingOnly && kind != BoxKind.MATCHING) continue;
            if (!kind.enabled(config)) continue;
            for (int slot : orderedSlots) {
                if (incoming.isEmpty()) break;
                ItemStack box = inventory.getItem(slot);
                if (!isShulker(box)) continue;

                ItemContainerContents stored = box.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
                // A nonstandard larger container must never be truncated to a vanilla shulker's 27 slots.
                if (stored.stream().count() > 27) continue;
                var contents = NonNullList.withSize(27, ItemStack.EMPTY);
                stored.copyInto(contents);
                if (classify(contents, incoming, config) != kind) continue;

                int destination = slot;
                if (box.getCount() > 1) {
                    if (!config.splitStackedBoxes) continue;
                    destination = findFreeMainSlot(inventory);
                    if (destination < 0) {
                        if (makeSpace) tryMakingSpace(inventory, incoming, config, box, contents, dropHandler);
                        continue;
                    }
                }

                int accepted = insert(contents, incoming);
                if (accepted == 0) continue;

                // Work on copies, then commit one box only. Mutating a stacked box in place duplicates contents.
                ItemStack filledBox = box.copyWithCount(1);
                filledBox.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(contents));
                inventory.setItem(destination, filledBox);
                if (destination != slot) box.shrink(1);
                incoming.shrink(accepted);
                inventory.setChanged();
            }
        }
    }

    private static void tryMakingSpace(Container inventory, ItemStack incoming, StorageConfig config,
                                       ItemStack stacked, NonNullList<ItemStack> contents,
                                       RelocationHandler dropHandler) {
        boolean dropping = config.makeSpaceMode == StorageConfig.MakeSpaceMode.DROP_AND_PICKUP;
        if (dropping && dropHandler == null) return;
        // A displaced stack of the incoming type can share its dedicated box.
        for (int index = 0; index < Inventory.INVENTORY_SIZE; index++) {
            int slot = (index + 9) % Inventory.INVENTORY_SIZE;
            if (slot < 9 && !config.useHotbarForSpace) continue;
            if (slot >= inventory.getContainerSize()) continue;
            ItemStack displaced = inventory.getItem(slot);
            if (displaced.isEmpty() || isShulker(displaced) || !displaced.getItem().canFitInsideContainerItems()) continue;
            if (!sameType(displaced, incoming, config)) continue;
            if (!config.allowPartialStacksForSpace && displaced.getCount() < displaced.getMaxStackSize()) continue;

            var combined = copyContents(contents);
            // The entire occupied slot must fit, even when it contains fewer than a full stack.
            if (insert(combined, displaced) != displaced.getCount()) continue;
            int accepted = insert(combined, incoming);
            if (accepted == 0) continue;
            if (dropping) {
                combined = copyContents(contents);
                insert(combined, incoming.copyWithCount(accepted));
            }
            ItemStack filled = stacked.copyWithCount(1);
            filled.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(combined));
            Runnable commit = () -> {
                inventory.setItem(slot, filled);
                stacked.shrink(1);
                incoming.shrink(accepted);
                inventory.setChanged();
            };
            if (dropping) dropHandler.relocate(List.of(new RelocatedStack(displaced.copy(), slot, filled)), commit);
            else commit.run();
            return;
        }

        var original = CraftingMaterials.copy(inventory);
        var planned = CraftingMaterials.copy(inventory);
        var transfers = new ArrayList<BoxRelocation.Transfer>();
        if (BoxRelocation.makeSpace(planned, config, BoxRelocation.ALL_SLOTS, incoming, transfers) < 0) return;
        var remainder = incoming.copy();
        storePass(planned, remainder, config, false, null);
        int accepted = incoming.getCount() - remainder.getCount();
        if (accepted == 0) return;

        if (dropping) {
            // Leave each displaced stack out until its own guarded pickup succeeds.
            for (var transfer : transfers) removeTransferred(planned, transfer);
        }
        Runnable commit = () -> {
            CraftingMaterials.commitChanges(original, planned, inventory);
            incoming.shrink(accepted);
        };
        if (dropping) dropHandler.relocate(relocatedFromPlan(planned, transfers), commit);
        else commit.run();
    }

    private static List<RelocatedStack> relocatedFromPlan(Container planned, List<BoxRelocation.Transfer> transfers) {
        return transfers.stream().map(transfer -> new RelocatedStack(transfer.stack(), transfer.destination(),
                planned.getItem(transfer.destination()))).toList();
    }

    private static void removeTransferred(Container planned, BoxRelocation.Transfer transfer) {
        var box = planned.getItem(transfer.destination());
        var contents = BoxRelocation.contents(box);
        int remaining = transfer.stack().getCount();
        for (var stack : contents) {
            if (!ItemStack.isSameItemSameComponents(stack, transfer.stack())) continue;
            int removed = Math.min(remaining, stack.getCount());
            stack.shrink(removed);
            remaining -= removed;
        }
        if (remaining != 0) throw new IllegalStateException("Relocation plan lost displaced items");
        box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(contents));
    }

    private static NonNullList<ItemStack> copyContents(NonNullList<ItemStack> original) {
        var copy = NonNullList.withSize(original.size(), ItemStack.EMPTY);
        for (int i = 0; i < original.size(); i++) copy.set(i, original.get(i).copy());
        return copy;
    }

    /** Only the synchronously reserved single box may receive a relocation pickup. */
    static int collectRelocated(Container inventory, int slot, ItemStack expectedBox, ItemStack incoming, StorageConfig config) {
        if (inventory.getItem(slot) != expectedBox || expectedBox.getCount() != 1) return 0;
        var contents = BoxRelocation.contents(expectedBox);
        if (contents == null) return 0;
        if (!BoxRelocation.acceptsType(contents, incoming, config)) return 0;
        int accepted = insert(contents, incoming);
        if (accepted > 0) {
            expectedBox.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(contents));
            incoming.shrink(accepted);
            inventory.setChanged();
        }
        return accepted;
    }

    @FunctionalInterface
    public interface RelocationHandler {
        // Spawn every transfer before committing. A rejected spawn leaves the inventory untouched.
        void relocate(List<RelocatedStack> displaced, Runnable commit);
    }

    public record RelocatedStack(ItemStack stack, int destination, ItemStack box) {}

    public static boolean isShulker(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof BlockItem block
                && block.getBlock() instanceof ShulkerBoxBlock;
    }

    private static int findFreeMainSlot(Container inventory) {
        for (int slot = 0; slot < Math.min(Inventory.INVENTORY_SIZE, inventory.getContainerSize()); slot++) {
            if (inventory.getItem(slot).isEmpty()) return slot;
        }
        return -1;
    }

    private static BoxKind classify(NonNullList<ItemStack> contents, ItemStack incoming, StorageConfig config) {
        if (contents.stream().allMatch(ItemStack::isEmpty)) return BoxKind.EMPTY;
        return BoxRelocation.acceptsType(contents, incoming, config) ? BoxKind.MATCHING : null;
    }

    private static boolean sameType(ItemStack first, ItemStack second, StorageConfig config) {
        return BoxRelocation.sameType(first, second, config);
    }

    static int insert(NonNullList<ItemStack> contents, ItemStack incoming) {
        int remaining = incoming.getCount();
        // Fill existing compatible stacks before occupying empty slots, just like a normal container.
        for (ItemStack stack : contents) {
            if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, incoming)) {
                int moved = Math.min(remaining, Math.max(0, stack.getMaxStackSize() - stack.getCount()));
                stack.grow(moved);
                remaining -= moved;
            }
        }
        for (int slot = 0; slot < contents.size() && remaining > 0; slot++) {
            if (contents.get(slot).isEmpty()) {
                int moved = Math.min(remaining, incoming.getMaxStackSize());
                contents.set(slot, incoming.copyWithCount(moved));
                remaining -= moved;
            }
        }
        return incoming.getCount() - remaining;
    }

    private enum BoxKind {
        MATCHING, EMPTY;

        boolean enabled(StorageConfig config) {
            return switch (this) {
                case MATCHING -> config.useMatchingBoxes;
                case EMPTY -> config.useEmptyBoxes;
            };
        }
    }
}
