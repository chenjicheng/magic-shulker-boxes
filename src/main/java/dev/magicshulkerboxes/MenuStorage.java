package dev.magicshulkerboxes;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/** Move only quantities actually received after the original action and all its callbacks finish. */
public final class MenuStorage {
    private MenuStorage() {}

    /** Unknown menus may retain a writer for any carried box; they keep exclusive ownership. */
    public static boolean canWriteCarriedContainers(ServerPlayer player) {
        var menu = player.containerMenu;
        if (menu == player.inventoryMenu) return true;
        for (var slot : menu.slots) {
            if (slot.container != player.getInventory()
                    && !ContainerOwnership.independent(slot.container, player)) return false;
        }
        return true;
    }

    public static void collectDeposited(Inventory inventory, Container before, StorageConfig config) {
        if (!config.pickupStorageEnabled
                || !(inventory.player instanceof ServerPlayer player)
                || !canWriteCarriedContainers(player)) return;
        for (int i = 0; i < 36; i++) {
            var current = inventory.getItem(i);
            int added = receivedQuantity(inventory, before, i);
            if (added <= 0 || ShulkerStorage.isShulker(current)) continue;
            // The plan removes newly received items before inserting them; a snapshot is never a source.
            var original = CraftingMaterials.copy(inventory);
            var planned = CraftingMaterials.copy(inventory);
            var remaining = planned.getItem(i).split(added);
            if (config.preferEmptyBoxesOverInventory)
                ShulkerStorage.store(planned, remaining, config, false, null);
            else ShulkerStorage.storeMatching(planned, remaining, config);
            if (!remaining.isEmpty()) {
                var destination = planned.getItem(i);
                if (destination.isEmpty()) CraftingMaterials.write(planned, i, remaining);
                else if (ItemStack.isSameItemSameComponents(destination, remaining))
                    destination.grow(remaining.getCount());
                else if (!CraftingMaterials.putBack(planned, remaining)) continue;
            }
            // Never overwrite a live change with the earlier planning snapshot.
            boolean unchanged = true;
            for (int slot = 0; slot < 36; slot++) {
                if (!ItemStack.matches(original.getItem(slot), inventory.getItem(slot))) {
                    unchanged = false;
                    break;
                }
            }
            if (unchanged) CraftingMaterials.commitChanges(original, planned, inventory);
        }
    }

    public static Container snapshot(Inventory inventory) {
        return CraftingMaterials.copy(inventory);
    }

    static int receivedQuantity(Container inventory, Container before, int slot) {
        var current = inventory.getItem(slot);
        var old = before.getItem(slot);
        int added = current.getCount() - (ItemStack.isSameItemSameComponents(current, old) ? old.getCount() : 0);
        // Slot increases can be rearrangement; only a matching whole-inventory net increase is received stock.
        return Math.max(0, Math.min(added, countLoose(inventory, current) - countLoose(before, current)));
    }

    private static int countLoose(Container inventory, ItemStack wanted) {
        int count = 0;
        for (int slot = 0; slot < 36; slot++) {
            var stack = inventory.getItem(slot);
            if (ItemStack.isSameItemSameComponents(stack, wanted)) count += stack.getCount();
        }
        return count;
    }
}
