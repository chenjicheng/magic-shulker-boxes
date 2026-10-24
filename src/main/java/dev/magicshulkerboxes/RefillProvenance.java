package dev.magicshulkerboxes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

/**
 * Transient, server-owned provenance for schematic refills. Nothing is written to item data, so a
 * dropped, stored or traded stack cannot carry a marker or leave one behind.
 */
public final class RefillProvenance {
    private RefillProvenance() {}

    private static final Map<UUID, List<Entry>> LEDGER = new HashMap<>();
    private static long nextId = 1;

    public record EntryView(ItemStack prototype, int anchorSlot, int sourceSlot, boolean ender,
                            int extracted, int remaining, int returned, int stored) {}
    public record SnapshotView(ItemStack prototype, int anchorSlot, int sourceSlot, boolean ender, int remaining) {}
    public record ReturnPlan(int returned, Runnable completion) {}

    private static final class Entry {
        final long id;
        final ItemStack prototype;
        final int extracted;
        final int sourceSlot;
        final boolean ender;
        final boolean sourceBox;
        int anchorSlot;
        int remaining;
        int returned;
        int stored;

        Entry(long id, ItemStack prototype, int extracted, int sourceSlot, boolean ender,
              boolean sourceBox, int anchorSlot) {
            this.id = id;
            this.prototype = prototype;
            this.extracted = extracted;
            this.sourceSlot = sourceSlot;
            this.ender = ender;
            this.sourceBox = sourceBox;
            this.anchorSlot = anchorSlot;
            this.remaining = extracted;
        }
    }

    /** Record a successful schematic placement in a hotbar slot. Plain backpack fallbacks are not tracked. */
    public static void record(Container inventory, UUID player, int anchorSlot, ItemStack taken, int count,
                              int sourceSlot, boolean ender, boolean sourceBox) {
        if (player == null || count <= 0 || taken.isEmpty() || anchorSlot < 0 || anchorSlot >= 9) return;
        reconcile(inventory, player);
        var list = LEDGER.computeIfAbsent(player, key -> new ArrayList<>());
        var entry = new Entry(nextId++, taken.copyWithCount(1), count, sourceSlot, ender, sourceBox, anchorSlot);
        list.add(entry);
        audit(entry);
    }

    /** Return the portion of a replaced stack that still belongs to this hotbar slot. */
    public static ReturnPlan planReturn(Container planned, UUID player, int anchorSlot, ItemStack displaced,
                                        StorageConfig config) {
        if (player == null || displaced.isEmpty()) return null;
        var list = LEDGER.get(player);
        if (list == null || list.isEmpty()) return null;
        Entry match = null;
        for (var entry : list) {
            if (entry.anchorSlot == anchorSlot && ItemStack.isSameItemSameComponents(entry.prototype, displaced)) {
                match = entry;
                break;
            }
        }
        if (match == null) return null;
        int marked = Math.min(match.remaining, displaced.getCount());
        if (marked <= 0) return null;
        int returned = insertIntoSource(planned, match, displaced, marked, config);
        int stored = marked - returned;
        Entry completed = match;
        return new ReturnPlan(returned, () -> {
            // Applying a plan twice must not count the same extraction twice.
            if (!list.remove(completed)) return;
            completed.returned += returned;
            completed.stored += stored;
            completed.remaining = 0;
            audit(completed);
            if (list.isEmpty()) LEDGER.remove(player);
        });
    }

    /** Drop entries whose stack left the main inventory/hotbar; cap counters to the live stack. */
    public static boolean reconcile(Container inventory, UUID player) {
        var list = LEDGER.get(player);
        if (list == null) return false;
        boolean changed = false;
        for (var iterator = list.iterator(); iterator.hasNext(); ) {
            var entry = iterator.next();
            if (entry.anchorSlot >= 0 && entry.anchorSlot < 36 && matches(inventory, entry.anchorSlot, entry.prototype)) {
                int count = inventory.getItem(entry.anchorSlot).getCount();
                if (count < entry.remaining) { entry.remaining = count; changed = true; }
            } else {
                int found = -1;
                int foundCount = 0;
                for (int slot = 0; slot < 36; slot++) {
                    if (!matches(inventory, slot, entry.prototype)) continue;
                    if (found >= 0) { found = -2; break; }
                    found = slot;
                    foundCount = inventory.getItem(slot).getCount();
                }
                if (found >= 0) {
                    entry.anchorSlot = found;
                    if (foundCount < entry.remaining) entry.remaining = foundCount;
                    changed = true;
                } else {
                    iterator.remove();
                    changed = true;
                    continue;
                }
            }
            if (entry.remaining <= 0) { iterator.remove(); changed = true; }
        }
        if (list.isEmpty()) LEDGER.remove(player);
        return changed;
    }

    public static void clear(UUID player) {
        if (player != null) LEDGER.remove(player);
    }

    public static void clearAll() {
        LEDGER.clear();
        nextId = 1;
    }

    public static List<EntryView> view(UUID player) {
        var list = LEDGER.get(player);
        if (list == null) return List.of();
        return list.stream().map(entry -> new EntryView(entry.prototype.copy(), entry.anchorSlot, entry.sourceSlot,
                entry.ender, entry.extracted, entry.remaining, entry.returned, entry.stored)).toList();
    }

    public static List<SnapshotView> snapshots(UUID player) {
        var list = LEDGER.get(player);
        if (list == null) return List.of();
        return list.stream().map(entry -> new SnapshotView(entry.prototype.copy(), entry.anchorSlot, entry.sourceSlot,
                entry.ender, entry.remaining)).toList();
    }

    private static boolean matches(Container inventory, int slot, ItemStack prototype) {
        if (slot < 0 || slot >= inventory.getContainerSize()) return false;
        var stack = inventory.getItem(slot);
        return !stack.isEmpty() && ItemStack.isSameItemSameComponents(prototype, stack);
    }

    private static int insertIntoSource(Container planned, Entry entry, ItemStack displaced, int amount,
                                        StorageConfig config) {
        if (entry.ender && !config.enderChestRefill) return 0;
        if (!entry.ender && entry.sourceSlot == Inventory.SLOT_OFFHAND && !config.includeOffhand) return 0;
        int slot = entry.sourceSlot;
        if (slot < 0 || slot >= planned.getContainerSize()) return 0;
        var source = planned.getItem(slot);
        var toReturn = displaced.copyWithCount(amount);
        if (entry.sourceBox) {
            // A stacked box shares one content set across its copies; never edit it in place.
            if (!ShulkerStorage.isShulker(source) || source.getCount() != 1) return 0;
            var contents = BoxRelocation.contents(source);
            if (contents == null) return 0;
            boolean junk = ManagedJunkBoxes.marked(source);
            if (!junk && !BoxRelocation.acceptsType(contents, toReturn, config)) return 0;
            int inserted = ShulkerStorage.insert(contents, toReturn);
            if (inserted == 0) return 0;
            var updated = source.copyWithCount(1);
            updated.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(contents));
            planned.setItem(slot, updated);
            return inserted;
        }
        if (ShulkerStorage.isShulker(source)) return 0;
        if (source.isEmpty()) {
            planned.setItem(slot, toReturn);
            return amount;
        }
        if (!ItemStack.isSameItemSameComponents(source, toReturn)) return 0;
        int moved = Math.min(amount, Math.max(0, source.getMaxStackSize() - source.getCount()));
        if (moved > 0) source.grow(moved);
        return moved;
    }

    /** Derived consumption is expected; only an over-count indicates a bug. */
    private static void audit(Entry entry) {
        if (entry.remaining < 0 || entry.returned < 0 || entry.stored < 0
                || entry.remaining + entry.returned + entry.stored > entry.extracted) {
            MagicShulkerBoxes.LOGGER.error(
                    "Refill provenance invariant failed: id={} item={} extracted={} remaining={} returned={} stored={}",
                    entry.id, BuiltInRegistries.ITEM.getKey(entry.prototype.getItem()),
                    entry.extracted, entry.remaining, entry.returned, entry.stored);
        }
    }
}
