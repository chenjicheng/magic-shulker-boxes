package dev.magicshulkerboxes;

import java.util.ArrayList;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.Container;

/** Short, synchronous reservations route our own drops back into their dedicated boxes. */
public final class PickupRelocation {
    public static final String RELOCATED_TAG = "magic_shulker_boxes:relocated";
    private static final ThreadLocal<Reservation> ACTIVE = new ThreadLocal<>();

    private PickupRelocation() {}

    public static ShulkerStorage.RelocationHandler handler(Inventory inventory) {
        return (transfers, commit) -> {
            var player = inventory.player;
            var droppedItems = new ArrayList<ItemEntity>();
            for (var transfer : transfers) {
                var dropped = new ItemEntity(player.level(), player.getX(), player.getY(), player.getZ(), transfer.stack().copy());
                dropped.setTarget(player.getUUID());
                dropped.setNoPickUpDelay();
                // Persist the guard if a mod blocks pickup or this item is saved on the ground.
                dropped.addTag(RELOCATED_TAG);
                if (!player.level().addFreshEntity(dropped)) {
                    droppedItems.forEach(ItemEntity::discard);
                    return;
                }
                droppedItems.add(dropped);
            }
            commit.run();
            var reservations = new ArrayList<Reservation>();
            for (int i = 0; i < transfers.size(); i++) {
                var transfer = transfers.get(i);
                var installed = inventory.getItem(transfer.destination());
                reservations.add(new Reservation(inventory, transfer.destination(),
                        ItemStack.matches(installed, transfer.box()) ? installed : transfer.box(), droppedItems.get(i).getItem()));
            }
            var previous = ACTIVE.get();
            try {
                for (int i = 0; i < droppedItems.size(); i++) {
                    ACTIVE.set(reservations.get(i));
                    droppedItems.get(i).playerTouch(player);
                }
            } finally {
                if (previous == null) ACTIVE.remove();
                else ACTIVE.set(previous);
            }
        };
    }

    /** -1 means this is an ordinary pickup, including any later retries of a blocked drop. */
    public static int collectReserved(Inventory inventory, ItemStack incoming, StorageConfig config) {
        var reservation = ACTIVE.get();
        if (reservation == null || reservation.inventory != inventory || reservation.incoming != incoming) return -1;
        return ShulkerStorage.collectRelocated(inventory, reservation.destination, reservation.box, incoming, config);
    }

    /** Native pickup may use a newly freed slot first. Move its actual receipt into the reserved box afterward. */
    public static boolean collectReceived(Inventory inventory, Container before, ItemStack originalStack, StorageConfig config) {
        var reservation = ACTIVE.get();
        if (reservation == null || reservation.inventory != inventory || reservation.incoming != originalStack) return false;
        if (inventory.getItem(reservation.destination) != reservation.box) return true;
        for (int slot = 0; slot < JunkSlots.SLOT_COUNT; slot++) {
            var current = inventory.getItem(slot);
            if (!ItemStack.isSameItemSameComponents(current, originalStack)) continue;
            int added = MenuStorage.receivedQuantity(inventory, before, slot);
            if (added == 0) continue;
            // Debit the real inventory receipt; the restored count on a removed entity is never used as stock.
            var received = current.split(added);
            ShulkerStorage.collectRelocated(inventory, reservation.destination, reservation.box, received, config);
            if (!received.isEmpty()) current.grow(received.getCount());
        }
        return true;
    }

    private record Reservation(Inventory inventory, int destination, ItemStack box, ItemStack incoming) {}
}
