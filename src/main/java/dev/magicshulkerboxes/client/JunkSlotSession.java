package dev.magicshulkerboxes.client;

import dev.magicshulkerboxes.JunkSlotsNetwork;
import dev.magicshulkerboxes.JunkSlots;

/** Reconcile a server-owned selection with one in-flight save and a newer locally queued choice. */
public final class JunkSlotSession {
    private boolean known;
    private long confirmed, desired, sent;
    private int revision, request;
    public void reset() { known = false; confirmed = desired = sent = 0; revision = request = 0; }
    public boolean known() { return known; }
    public boolean pending() { return request != 0; }
    public boolean dirty() { return known && desired != confirmed; }
    public int revision() { return revision; }
    public int request() { return request; }
    public long confirmed() { return confirmed; }
    public long desired() { return desired; }
    public void choose(long mask) { JunkSlots.validate(mask); if (known) desired = mask; }
    public void requesting(int value) {
        if (value <= 0 || !dirty() || pending()) throw new IllegalStateException("No junk selection ready for saving");
        request = value; sent = desired;
    }
    public boolean receive(JunkSlotsNetwork.State state) {
        if ((state.request() == 0 && pending()) || (state.request() != 0 && state.request() != request)) return false;
        try { JunkSlots.validate(state.mask()); }
        catch (IllegalArgumentException exception) { reset(); return false; }
        if (state.revision() < 0) { reset(); return true; }
        if (state.status() < JunkSlotsNetwork.SAVED || state.status() > JunkSlotsNetwork.FAILED) return false;
        known = true; confirmed = state.mask(); revision = state.revision();
        switch (state.status()) {
            case JunkSlotsNetwork.SAVED, JunkSlotsNetwork.SNAPSHOT -> {
                if (state.request() == 0 || desired == sent) desired = confirmed;
            }
            case JunkSlotsNetwork.BUSY -> { /* Keep the unsaved choice for one coalesced retry. */ }
            default -> desired = confirmed;
        }
        request = 0;
        return true;
    }
}
