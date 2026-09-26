package dev.magicshulkerboxes;

import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/** A short, synchronous reservation routes our own drop back into the newly split box. */
public final class PickupRelocation {
    public static final String RELOCATED_TAG = "magic_shulker_boxes:relocated";
    private static final ThreadLocal<Reservation> ACTIVE = new ThreadLocal<>();

    private PickupRelocation() {}

    public static ShulkerStorage.RelocationHandler handler(Inventory inventory) {
        return (displaced, destination, filledBox, commit) -> {
            var player = inventory.player;
            var dropped = new ItemEntity(player.level(), player.getX(), player.getY(), player.getZ(), displaced);
            dropped.setTarget(player.getUUID());
            dropped.setNoPickUpDelay();
            // Persist the guard if another mod blocks pickup or the world is saved with this item still on the ground.
            dropped.addTag(RELOCATED_TAG);
            if (!player.level().addFreshEntity(dropped)) return;
            commit.run();
            var previous = ACTIVE.get();
            ACTIVE.set(new Reservation(inventory, destination, filledBox, dropped.getItem()));
            try {
                dropped.playerTouch(player);
            } finally {
                if (previous == null) ACTIVE.remove();
                else ACTIVE.set(previous);
            }
        };
    }

    /** -1 means this is an ordinary pickup, including any later retries of a blocked drop. */
    public static int collectReserved(Inventory inventory, ItemStack incoming) {
        var reservation = ACTIVE.get();
        if (reservation == null || reservation.inventory != inventory || reservation.incoming != incoming) return -1;
        return ShulkerStorage.collectRelocated(inventory, reservation.destination, reservation.box, incoming);
    }

    private record Reservation(Inventory inventory, int destination, ItemStack box, ItemStack incoming) {}
}
