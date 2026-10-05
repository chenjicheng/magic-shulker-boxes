package dev.magicshulkerboxes;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

/** Post-pickup processing keeps removed entities and their vanilla-restored stacks out of storage. */
public final class PickupStorage {
    private PickupStorage() {}

    public static void finish(ItemEntity entity, ServerPlayer player, Container before,
                              ItemStack originalStack, int remaining, boolean vanillaAccepted) {
        var config = MagicShulkerBoxes.configFor(player);
        if (!config.pickupStorageEnabled || !MenuStorage.canWriteCarriedContainers(player)) return;
        MenuStorage.collectDeposited(player.getInventory(), before, config);
        // Vanilla restores a removed entity's stack count for callbacks. It is no longer live stock.
        if (entity.isRemoved() || entity.getItem() != originalStack || originalStack.isEmpty()
                || originalStack.getCount() != remaining) return;
        int stored = PickupRelocation.collectReserved(player.getInventory(), originalStack, config);
        if (stored < 0) {
            boolean makeSpace = !entity.getTags().contains(PickupRelocation.RELOCATED_TAG);
            stored = ShulkerStorage.store(player.getInventory(), originalStack, config,
                    makeSpace, PickupRelocation.handler(player.getInventory()));
        }
        if (stored > 0) {
            if (!vanillaAccepted) {
                player.take(entity, stored);
                player.awardStat(Stats.ITEM_PICKED_UP.get(originalStack.getItem()), stored);
                player.onItemPickup(entity);
            }
            if (originalStack.isEmpty()) entity.discard();
        }
        if (!originalStack.isEmpty() && !ShulkerStorage.isShulker(originalStack)
                && originalStack.getItem().canFitInsideContainerItems()) StorageFailure.noSpace(player);
    }
}
