package dev.magicshulkerboxes;

import net.minecraft.world.entity.player.Inventory;

/** Bits name native main-inventory slots, independent of container-menu numbering. */
public final class JunkSlots {
    public static final int SLOT_COUNT = Inventory.INVENTORY_SIZE;
    public static final long VALID_MASK = (1L << SLOT_COUNT) - 1;

    private JunkSlots() {}

    public static boolean selected(long mask, int slot) {
        return slot >= 0 && slot < SLOT_COUNT && (mask & (1L << slot)) != 0;
    }

    public static void validate(long mask) {
        if (mask < 0 || (mask & ~VALID_MASK) != 0) throw new IllegalArgumentException("Invalid junk slot mask");
    }
}
