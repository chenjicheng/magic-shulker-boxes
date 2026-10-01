package dev.magicshulkerboxes;

import java.util.function.BooleanSupplier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.MerchantResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Keep menu permissions and result callbacks in vanilla; collect only transfers into ordinary
 * player slots.
 */
public final class MenuStorage {
    private MenuStorage() {}

    public static boolean transfer(
            AbstractContainerMenu menu,
            ItemStack incoming,
            int start,
            int end,
            boolean reverse,
            BooleanSupplier vanilla) {
        if (menu instanceof AbstractCraftingMenu
                || incoming.isEmpty()
                || start < 0
                || end > menu.slots.size()
                || start >= end) return vanilla.getAsBoolean();
        if (!(menu.getSlot(start).container instanceof Inventory inventory)
                || !(inventory.player instanceof ServerPlayer player)
                || player.containerMenu != menu
                || !menu.stillValid(player)) return vanilla.getAsBoolean();
        for (int i = start; i < end; i++) {
            var slot = menu.getSlot(i);
            if (slot.container != inventory
                    || slot.getContainerSlot() >= 36
                    || slot.getClass() != Slot.class) return vanilla.getAsBoolean();
        }
        var source =
                menu.slots.stream()
                        .filter(s -> s.container != inventory && s.getItem() == incoming)
                        .findFirst()
                        .orElse(null);
        if (source == null || !source.mayPickup(player)) return vanilla.getAsBoolean();
        var config = MagicShulkerBoxes.configFor(player);
        if (!config.pickupStorageEnabled) return vanilla.getAsBoolean();
        // Trade outputs are indivisible: never charge a full offer for a partial result.
        if (source instanceof MerchantResultSlot
                && !fits(menu, inventory, incoming, start, end, reverse, config)) return false;
        int stored = 0;
        if (!config.onlyWhenInventoryFull)
            stored = ShulkerStorage.store(inventory, incoming, config, true, null);
        boolean accepted = !incoming.isEmpty() && vanilla.getAsBoolean();
        if (config.onlyWhenInventoryFull)
            stored = ShulkerStorage.store(inventory, incoming, config, true, null);
        return accepted || stored > 0;
    }

    private static boolean fits(
            AbstractContainerMenu menu,
            Inventory inventory,
            ItemStack stack,
            int start,
            int end,
            boolean reverse,
            StorageConfig config) {
        var copy = CraftingMaterials.copy(inventory);
        var remaining = stack.copy();
        if (!config.onlyWhenInventoryFull)
            ShulkerStorage.store(copy, remaining, config, true, null);
        // Mirror vanilla's merging and its single empty-slot pass, including destination stack
        // limits.
        for (int pass = 0; pass < 2 && !remaining.isEmpty(); pass++) {
            for (int offset = 0; offset < end - start && !remaining.isEmpty(); offset++) {
                var slot = menu.getSlot(reverse ? end - 1 - offset : start + offset);
                var current = copy.getItem(slot.getContainerSlot());
                if (pass == 0
                        && remaining.isStackable()
                        && !current.isEmpty()
                        && ItemStack.isSameItemSameComponents(current, remaining)) {
                    int moved =
                            Math.min(
                                    remaining.getCount(),
                                    Math.max(
                                            0, slot.getMaxStackSize(current) - current.getCount()));
                    current.grow(moved);
                    remaining.shrink(moved);
                } else if (pass == 1 && current.isEmpty() && slot.mayPlace(remaining)) {
                    int moved = Math.min(remaining.getCount(), slot.getMaxStackSize(remaining));
                    copy.setItem(slot.getContainerSlot(), remaining.copyWithCount(moved));
                    remaining.shrink(moved);
                    break;
                }
            }
        }
        if (config.onlyWhenInventoryFull) ShulkerStorage.store(copy, remaining, config, true, null);
        return remaining.isEmpty();
    }

    public static void collectDeposited(
            Inventory inventory, net.minecraft.world.Container before, StorageConfig config) {
        if (!config.pickupStorageEnabled || config.onlyWhenInventoryFull) return;
        for (int i = 0; i < 36; i++) {
            var current = inventory.getItem(i);
            var old = before.getItem(i);
            int added =
                    current.getCount()
                            - (ItemStack.isSameItemSameComponents(current, old)
                                    ? old.getCount()
                                    : 0);
            if (added <= 0 || ShulkerStorage.isShulker(current)) continue;
            // Remove only the newly deposited quantity before storage can use this slot to split a
            // stacked box.
            var original = CraftingMaterials.copy(inventory);
            var planned = CraftingMaterials.copy(inventory);
            var rest = planned.getItem(i);
            var remaining = rest.split(added);
            ShulkerStorage.store(planned, remaining, config, false, null);
            if (!remaining.isEmpty()) {
                var destination = planned.getItem(i);
                if (destination.isEmpty()) CraftingMaterials.write(planned, i, remaining);
                else if (ItemStack.isSameItemSameComponents(destination, remaining))
                    destination.grow(remaining.getCount());
                else if (!CraftingMaterials.putBack(planned, remaining)) continue;
            }
            CraftingMaterials.commitChanges(original, planned, inventory);
        }
        inventory.setChanged();
    }

    public static net.minecraft.world.Container snapshot(Inventory inventory) {
        return CraftingMaterials.copy(inventory);
    }
}
