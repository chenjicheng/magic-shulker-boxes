package dev.magicshulkerboxes.client;

import dev.magicshulkerboxes.JunkSlots;
import java.util.LinkedHashSet;
import java.util.function.Supplier;

/** Add position reservations to IPN's own sorting lock projection without changing its stored locks. */
public final class IpnJunkSlots {
    private static final ThreadLocal<Boolean> SORTING = ThreadLocal.withInitial(() -> false);
    private IpnJunkSlots() {}

    public static <T> T sorting(Supplier<T> action) {
        boolean previous = SORTING.get(); SORTING.set(true);
        try { return action.get(); }
        finally { if (previous) SORTING.set(true); else SORTING.remove(); }
    }

    public static Iterable<Integer> locks(Iterable<Integer> original) {
        long selected = JunkSlotsClient.confirmedMask();
        if (!SORTING.get() || selected == 0) return original;
        var projected = new LinkedHashSet<Integer>();
        original.forEach(projected::add);
        for (int slot = 0; slot < JunkSlots.SLOT_COUNT; slot++) if (JunkSlots.selected(selected, slot)) projected.add(slot);
        return projected;
    }
}
