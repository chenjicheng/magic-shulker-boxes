package dev.magicshulkerboxes.client;

import dev.magicshulkerboxes.JunkSlots;

/** One held-key gesture paints one mode, visiting each native slot at most once. */
public final class JunkSlotGesture {
    private long mask;
    private long visited;
    private Boolean adding;
    public void begin(long initial) {
        JunkSlots.validate(initial);
        mask = initial; visited = 0; adding = null;
    }
    public boolean visit(int slot) {
        if (slot < 0 || slot >= JunkSlots.SLOT_COUNT || JunkSlots.selected(visited, slot)) return false;
        long bit = 1L << slot;
        visited |= bit;
        if (adding == null) adding = !JunkSlots.selected(mask, slot);
        long previous = mask;
        if (adding) mask |= bit; else mask &= ~bit;
        return previous != mask;
    }
    public long mask() { return mask; }
}
