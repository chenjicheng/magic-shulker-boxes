package dev.magicshulkerboxes;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.component.ItemContainerContents;

/** Shared, read-only ordering. Ties retain inventory slot order, including the optional offhand. */
final class BoxOrder {
    private BoxOrder() {}
    private record Candidate(int slot, double fullness) {}

    static List<Integer> fullestFirst(Container inventory, StorageConfig config) {
        return slots(inventory, config, Comparator.comparingDouble(Candidate::fullness).reversed());
    }

    static List<Integer> emptiestFirst(Container inventory, StorageConfig config) {
        return slots(inventory, config, Comparator.comparingDouble(Candidate::fullness));
    }

    private static List<Integer> slots(Container inventory, StorageConfig config, Comparator<Candidate> order) {
        var candidates = new ArrayList<Candidate>();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (slot >= Inventory.INVENTORY_SIZE && !(config.includeOffhand && slot == Inventory.SLOT_OFFHAND)
                    && !(config.enderChestRefill && RefillSources.isEnder(inventory, slot))) continue;
            var box = inventory.getItem(slot);
            if (!ShulkerStorage.isShulker(box)) continue;
            var contents = box.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).stream().toList();
            if (contents.size() > 27) continue;
            double fullness = 0;
            for (var stack : contents) {
                // A tool occupies one whole slot; a single block uses only 1/64 of a stack.
                if (!stack.isEmpty()) fullness += Math.min(1.0, (double) stack.getCount() / stack.getMaxStackSize());
            }
            candidates.add(new Candidate(slot, fullness));
        }
        candidates.sort(Comparator.<Candidate, Boolean>comparing(candidate -> RefillSources.isEnder(inventory, candidate.slot()))
                .thenComparing(order).thenComparingInt(Candidate::slot));
        return candidates.stream().map(Candidate::slot).toList();
    }
}
