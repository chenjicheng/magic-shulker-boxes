package dev.magicshulkerboxes;

/** The nine ordinary settings inherit, persist and enforce permissions like the existing hotbar switch. */
public final class HotbarProtection {
    private HotbarProtection() {}

    static boolean protectedSlot(int slot, StorageConfig config) {
        return switch (slot) {
            case 0 -> config.protectHotbarSlot1;
            case 1 -> config.protectHotbarSlot2;
            case 2 -> config.protectHotbarSlot3;
            case 3 -> config.protectHotbarSlot4;
            case 4 -> config.protectHotbarSlot5;
            case 5 -> config.protectHotbarSlot6;
            case 6 -> config.protectHotbarSlot7;
            case 7 -> config.protectHotbarSlot8;
            case 8 -> config.protectHotbarSlot9;
            default -> false;
        };
    }

    static long unprotectedSlots(long eligible, StorageConfig config) {
        for (int slot = 0; slot < 9; slot++) if (protectedSlot(slot, config)) eligible &= ~(1L << slot);
        return eligible;
    }
}
