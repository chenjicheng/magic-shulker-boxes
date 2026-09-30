package dev.magicshulkerboxes.client;

import dev.magicshulkerboxes.ShulkerStorage;
import dev.magicshulkerboxes.mixin.IpnSlotFinder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import kotlin.collections.IndexedValue;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.component.PatchedDataComponentMap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import org.anti_ad.mc.ipnext.event.autorefill.AutoRefillHandler$ItemSlotMonitor;
import org.anti_ad.mc.ipnext.item.ItemType;

/** A scoped, read-only candidate list. All filtering and sorting run in IPN's original method. */
public final class IpnCandidates {
    private static final int VIRTUAL_SLOT_START = 1000;
    private static final ThreadLocal<Lookup> LOOKUP = new ThreadLocal<>();
    private IpnCandidates() {}
    public record Source(int boxSlot, int contentSlot, int boxCount, ItemStack stack, int eligibleSlots) {}
    private static final class Lookup {
        final Map<Integer, Source> sources = new HashMap<>();
    }

    static IpnSlotFinder finder() { return (IpnSlotFinder) (Object) AutoRefillHandler$ItemSlotMonitor.Companion; }

    public static Source find(org.anti_ad.mc.ipnext.item.ItemStack checking, org.anti_ad.mc.ipnext.item.ItemStack current) {
        var previous = LOOKUP.get();
        var lookup = new Lookup();
        LOOKUP.set(lookup);
        try {
            var slot = finder().msb$findCorrespondingSlot(checking, current);
            return slot == null ? null : lookup.sources.get(slot);
        } finally {
            if (previous == null) LOOKUP.remove(); else LOOKUP.set(previous);
        }
    }

    /** Called only by the Mixin at IPN's candidate-list boundary; normal lookups retain the original iterable. */
    public static Iterable<IndexedValue<org.anti_ad.mc.ipnext.item.ItemStack>> extend(
            Iterable<IndexedValue<org.anti_ad.mc.ipnext.item.ItemStack>> original) {
        var lookup = LOOKUP.get();
        if (lookup == null) return original;
        var client = Minecraft.getInstance();
        var slots = new ArrayList<Integer>();
        int eligible = 0;
        for (var entry : original) {
            int slot = entry.getIndex() + 9;
            if (slot >= 9 && slot < 36) { slots.add(slot); eligible |= 1 << (slot - 9); }
        }
        var candidates = new ArrayList<IndexedValue<org.anti_ad.mc.ipnext.item.ItemStack>>();
        for (int slot : slots) {
            var box = client.player.getInventory().getItem(slot);
            if (!ShulkerStorage.isShulker(box) || (box.getCount() > 1 && !RefillSettings.get().splitStackedBoxes)) continue;
            var stored = box.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
            if (stored.stream().count() > 27) continue;
            var contents = net.minecraft.core.NonNullList.withSize(27, ItemStack.EMPTY); stored.copyInto(contents);
            for (int inner = 0; inner < 27; inner++) {
                var stack = contents.get(inner);
                if (stack.isEmpty() || !stack.getItem().canFitInsideContainerItems()) continue;
                int virtualSlot = VIRTUAL_SLOT_START + lookup.sources.size();
                lookup.sources.put(virtualSlot, new Source(slot, inner, box.getCount(), stack.copy(), eligible));
                candidates.add(new IndexedValue<>(virtualSlot - 9, wrap(stack)));
            }
        }
        return candidates;
    }

    public static org.anti_ad.mc.ipnext.item.ItemStack wrap(ItemStack original) {
        var stack = original.copy();
        var type = new ItemType(stack.getItem(), new PatchedDataComponentMap(stack.getComponents()), stack.getComponentsPatch(),
                stack::isDamageableItem, false, stack.isDamageableItem(), ignored -> null);
        return org.anti_ad.mc.ipnext.item.ItemStack.Companion.invoke(type, stack.getCount());
    }
}
