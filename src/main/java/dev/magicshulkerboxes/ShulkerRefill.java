package dev.magicshulkerboxes;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

/** Atomic server-side extraction; clients choose items, but never supply authoritative item data. */
public final class ShulkerRefill {
    private ShulkerRefill() {}
    public static int take(Container inventory, int boxSlot, int contentSlot, Item expected, StorageConfig config) {
        if (!config.schematicRefill) return 0;
        return take(inventory, boxSlot, contentSlot, expected, config, config.refillAmount, BoxRelocation.ALL_SLOTS, true);
    }

    /** IPN uses backpack boxes and optional own ender sources; its mask protects backpack destinations. */
    public static int takeForRestock(Container inventory, int boxSlot, int contentSlot, StorageConfig config, int eligibleSlots) {
        if (!config.ipnRefill || (!(boxSlot >= 9 && boxSlot < 36) && !(config.enderChestRefill && RefillSources.isEnder(inventory, boxSlot)))
                || eligibleSlots <= 0 || eligibleSlots >= (1 << 27)) return 0;
        return take(inventory, boxSlot, contentSlot, null, config, config.refillAmount, (long) eligibleSlots << 9, false);
    }

    private static int take(Container inventory, int boxSlot, int contentSlot, Item expected, StorageConfig config,
                            int amount, long eligibleSlots, boolean preferHotbar) {
        eligibleSlots = HotbarProtection.unprotectedSlots(eligibleSlots, config);
        int size = Math.min(36, inventory.getContainerSize());
        boolean offhand = config.includeOffhand && boxSlot == Inventory.SLOT_OFFHAND && boxSlot < inventory.getContainerSize();
        boolean ender = config.enderChestRefill && RefillSources.isEnder(inventory, boxSlot);
        if (ender && contentSlot == -1)
            return takeDirect(inventory, boxSlot, expected, config, amount, eligibleSlots, preferHotbar);
        if (boxSlot < 0 || (boxSlot >= size && !offhand && !ender)
                || contentSlot < 0 || contentSlot >= 27) return 0;
        var box = inventory.getItem(boxSlot);
        if (!ShulkerStorage.isShulker(box) || (box.getCount() > 1 && !config.splitStackedBoxes)) return 0;
        var container = box.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
        if (container.stream().count() > 27) return 0;
        var original = NonNullList.withSize(27, ItemStack.EMPTY);
        container.copyInto(original);
        var source = original.get(contentSlot);
        if (source.isEmpty() || (expected != null && !source.is(expected)) || !source.getItem().canFitInsideContainerItems()) return 0;
        int count = Math.min(source.getCount(), Math.min(amount, source.getMaxStackSize()));
        var taken = source.copyWithCount(count);

        var before = CraftingMaterials.copy(inventory);
        var planned = CraftingMaterials.copy(inventory);
        ServerPlayer player = preferHotbar ? RefillSources.playerOf(inventory) : null;
        UUID playerId = player == null ? null : player.getUUID();
        if (playerId != null) RefillProvenance.reconcile(inventory, playerId);
        // The destination is reserved before splitting so a new split box can never occupy it.
        // Read the selected slot from the real inventory; the transaction snapshot is a plain container.
        int destination = preferHotbar ? hotbarDestination(inventory, config) : -1;
        if (destination < 0) destination = BoxRelocation.freeSlot(planned, eligibleSlots);
        int splitSlot = boxSlot;
        if (box.getCount() > 1) {
            if (ender) splitSlot = RefillSources.freeBoxSlot(planned, boxSlot, config);
            else splitSlot = BoxRelocation.freeSlot(planned,
                    (destination < 0 ? eligibleSlots : eligibleSlots & ~(1L << destination)));
            if (splitSlot < 0) return 0;
            planned.getItem(boxSlot).shrink(1);
        }
        original.get(contentSlot).shrink(count);
        var changedBox = box.copyWithCount(1);
        changedBox.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(original));
        CraftingMaterials.write(planned, splitSlot, changedBox);
        var ledgerActions = new ArrayList<Runnable>();
        int placed = place(player, planned, taken, config, eligibleSlots, destination, ledgerActions);
        if (placed < 0) return 0;
        int sourceSlot = splitSlot;
        if (player != null && placed < 9) {
            ledgerActions.add(() -> RefillProvenance.record(inventory, player.getUUID(), placed, taken,
                    taken.getCount(), sourceSlot, ender, true));
        }
        ManagedJunkBoxes.consolidate(planned, taken, config, eligibleSlots, 0);
        ManagedJunkBoxes.consolidateStored(planned, config, eligibleSlots, taken);
        CraftingMaterials.commitChanges(before, planned, inventory);
        ledgerActions.forEach(Runnable::run);
        if (player != null) RefillProvenanceNetwork.sync(player);
        return count;
    }

    /** The held stack receives the material so Litematica never needs its own inventory swap. */
    private static int hotbarDestination(Container inventory, StorageConfig config) {
        int size = Math.min(9, inventory.getContainerSize());
        if (size <= 0) return -1;
        int selected = RefillSources.selectedSlot(inventory);
        if (selected >= 0 && selected < size && inventory.getItem(selected).isEmpty()) return selected;
        for (int slot = 0; slot < size; slot++) if (inventory.getItem(slot).isEmpty()) return slot;
        // Replacing a non-empty slot moves its stack, which only automatic making-space may do.
        if (!config.refillMakeSpace) return -1;
        if (selected >= 0 && selected < size && replaceableHotbarStack(inventory.getItem(selected), config)) return selected;
        for (int slot = 0; slot < size; slot++) if (replaceableHotbarStack(inventory.getItem(slot), config)) return slot;
        return -1;
    }

    private static boolean replaceableHotbarStack(ItemStack stack, StorageConfig config) {
        // Tools and equipment stay in the hotbar; Litematica's own pick-block target skips them too.
        return !stack.isEmpty() && !ShulkerStorage.isShulker(stack) && !stack.isDamageableItem()
                && stack.getItem().canFitInsideContainerItems()
                && (config.allowPartialStacksForSpace || stack.getCount() >= stack.getMaxStackSize());
    }

    private static int place(ServerPlayer player, Container planned, ItemStack taken, StorageConfig config,
                             long eligibleSlots, int preferredDestination, List<Runnable> ledgerActions) {
        int destination = preferredDestination;
        if (destination < 0) destination = BoxRelocation.freeSlot(planned, eligibleSlots);
        if (destination < 0) {
            if (!config.refillMakeSpace) return -1;
            destination = BoxRelocation.makeSpace(planned, config, eligibleSlots, taken, null);
        }
        if (destination < 0) return -1;
        var displaced = planned.getItem(destination).copy();
        if (player != null && !displaced.isEmpty()) {
            // The marked portion returns to its source box before the rest follows normal storage.
            var plan = RefillProvenance.planReturn(planned, player.getUUID(), destination, displaced, config);
            if (plan != null) {
                if (plan.returned() > 0) displaced.shrink(plan.returned());
                ledgerActions.add(plan.completion());
            }
        }
        CraftingMaterials.write(planned, destination, taken);
        if (!displaced.isEmpty()) {
            if (BoxRelocation.store(planned, displaced, -1, config, eligibleSlots) < 0) {
                // A filled hotbar may still allow an ordinary backpack fallback instead of losing the replaced stack.
                int fallback = BoxRelocation.freeSlot(planned, eligibleSlots & ~0x1FFL);
                if (fallback < 0) return -1;
                CraftingMaterials.write(planned, fallback, displaced);
            }
        }
        return destination;
    }

    private static int takeDirect(Container inventory, int sourceSlot, Item expected, StorageConfig config,
                                  int amount, long eligible, boolean preferHotbar) {
        var source = inventory.getItem(sourceSlot);
        if (source.isEmpty() || ShulkerStorage.isShulker(source) || (expected != null && !source.is(expected))) return 0;
        int count = Math.min(source.getCount(), Math.min(amount, source.getMaxStackSize()));
        var original = CraftingMaterials.copy(inventory); var planned = CraftingMaterials.copy(inventory);
        var remaining = source.copyWithCount(count);
        if (preferHotbar) {
            ServerPlayer player = RefillSources.playerOf(inventory);
            UUID playerId = player == null ? null : player.getUUID();
            if (playerId != null) RefillProvenance.reconcile(inventory, playerId);
            var ledgerActions = new ArrayList<Runnable>();
            final var recordStack = remaining;
            if (!remaining.isEmpty()) {
                int placed = place(player, planned, remaining, config, eligible,
                        hotbarDestination(inventory, config), ledgerActions);
                if (placed < 0) return 0;
                if (player != null && placed < 9) {
                    ledgerActions.add(() -> RefillProvenance.record(inventory, player.getUUID(), placed, recordStack,
                            recordStack.getCount(), sourceSlot, true, false));
                }
            }
            planned.getItem(sourceSlot).shrink(count);
            CraftingMaterials.commitChanges(original, planned, inventory);
            ledgerActions.forEach(Runnable::run);
            if (player != null) RefillProvenanceNetwork.sync(player);
            return count;
        }
        for (int pass = 0; pass < 2 && !remaining.isEmpty(); pass++) {
            for (int i = 0; i < 36 && !remaining.isEmpty(); i++) {
                int slot = (i + 9) % 36;
                if ((eligible & (1L << slot)) == 0) continue;
                var current = planned.getItem(slot);
                if (pass == 0 && !current.isEmpty() && ItemStack.isSameItemSameComponents(current, remaining)) {
                    int moved = Math.min(remaining.getCount(), Math.max(0, current.getMaxStackSize() - current.getCount()));
                    current.grow(moved); remaining.shrink(moved);
                } else if (pass == 1 && current.isEmpty()) {
                    CraftingMaterials.write(planned, slot, remaining); remaining = ItemStack.EMPTY;
                }
            }
        }
        if (!remaining.isEmpty()) return 0;
        planned.getItem(sourceSlot).shrink(count);
        CraftingMaterials.commitChanges(original, planned, inventory);
        return count;
    }

    /** IPN selected a tool: equip it and store the old tool in a compatible box in one transaction. */
    public static int swapToolForRestock(Container inventory, int boxSlot, int inner, int targetSlot,
                                         StorageConfig config, int eligible) {
        if (!config.ipnRefill || eligible <= 0 || eligible >= (1 << 27)
                || targetSlot >= inventory.getContainerSize()
                || !((targetSlot >= 0 && targetSlot < 9) || targetSlot == Inventory.SLOT_OFFHAND)) return 0;
        boolean ender = config.enderChestRefill && RefillSources.isEnder(inventory, boxSlot);
        if (!ender && (boxSlot < 9 || boxSlot >= 36 || (eligible & (1 << (boxSlot - 9))) == 0)) return 0;
        var replacement = RefillSources.item(inventory, boxSlot, inner); var old = inventory.getItem(targetSlot);
        if (!replacement.isDamageableItem() || replacement.getCount() != 1 || (!old.isEmpty()
                && (!old.isDamageableItem() || old.getCount() != 1 || !old.getItem().canFitInsideContainerItems()))) return 0;
        if (inner == -1 && ender) {
            inventory.setItem(boxSlot, old.copy()); inventory.setItem(targetSlot, replacement.copy()); inventory.setChanged(); return 1;
        }
        var box = inventory.getItem(boxSlot); int destination = boxSlot;
        if (box.getCount() > 1) {
            if (!config.splitStackedBoxes) return 0;
            destination = -1;
            if (ender) destination = RefillSources.freeBoxSlot(inventory, boxSlot, config);
            else for (int slot = 9; slot < 36; slot++) {
                if ((eligible & (1 << (slot - 9))) != 0
                        && inventory.getItem(slot).isEmpty()) { destination = slot; break; }
            }
            if (destination < 0) return 0;
        }
        var contents = NonNullList.withSize(27, ItemStack.EMPTY);
        box.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).copyInto(contents);
        contents.set(inner, ItemStack.EMPTY);
        var changed = box.copyWithCount(1); changed.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(contents));
        var before = CraftingMaterials.copy(inventory);
        var planned = CraftingMaterials.copy(inventory);
        if (box.getCount() > 1) planned.getItem(boxSlot).shrink(1);
        if (!old.isEmpty() && BoxRelocation.acceptsType(contents, old, config)) {
            // Keep the original tool slot when the remaining source is compatible.
            contents.set(inner, old.copy());
            changed.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(contents));
        }
        CraftingMaterials.write(planned, destination, changed);
        if (!old.isEmpty() && contents.get(inner).isEmpty()
                && BoxRelocation.store(planned, old, -1, config, (long) eligible << 9) < 0) return 0;
        CraftingMaterials.write(planned, targetSlot, replacement);
        CraftingMaterials.commitChanges(before, planned, inventory);
        return 1;
    }
}
