package dev.magicshulkerboxes;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/** Process detached, budgeted receipts only after vanilla source deductions and callbacks finish. */
public final class MenuStorage {
    private MenuStorage() {}
    private record Receipt(int slot, ItemStack stack) {}
    private static final class Budget {
        final ItemStack prototype;
        int remaining;
        Budget(ItemStack prototype, int remaining) { this.prototype = prototype; this.remaining = remaining; }
    }

    public static boolean canWriteCarriedContainers(ServerPlayer player) {
        var menu = player.containerMenu;
        if (menu == player.inventoryMenu) return true;
        for (var slot : menu.slots) {
            if (slot.container != player.getInventory() && !ContainerOwnership.independent(slot.container, player)) return false;
        }
        return true;
    }

    public static void collectDeposited(Inventory inventory, Container before, StorageConfig config) {
        if (!config.pickupStorageEnabled || !(inventory.player instanceof ServerPlayer player)
                || !canWriteCarriedContainers(player)) return;
        var groups = new ArrayList<List<Receipt>>();
        for (var receipt : receipts(inventory, before)) {
            var group = groups.stream().filter(existing -> BoxRelocation.sameType(existing.getFirst().stack(), receipt.stack(), config))
                    .findFirst().orElse(null);
            if (group == null) { group = new ArrayList<>(); groups.add(group); }
            group.add(receipt);
        }
        for (var group : groups) collectGroup(inventory, group, config);
    }

    /** Positive slot deltas share one net budget per full component variant, rather than each spending it again. */
    private static List<Receipt> receipts(Container inventory, Container before) {
        var budgets = new ArrayList<Budget>();
        var receipts = new ArrayList<Receipt>();
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            var current = inventory.getItem(slot);
            if (current.isEmpty() || ShulkerStorage.isShulker(current) || !current.getItem().canFitInsideContainerItems()) continue;
            var old = before.getItem(slot);
            int added = current.getCount() - (ItemStack.isSameItemSameComponents(current, old) ? old.getCount() : 0);
            if (added <= 0) continue;
            var budget = budgets.stream().filter(value -> ItemStack.isSameItemSameComponents(value.prototype, current)).findFirst().orElse(null);
            if (budget == null) {
                budget = new Budget(current.copyWithCount(1), Math.max(0, countLoose(inventory, current) - countLoose(before, current)));
                budgets.add(budget);
            }
            int actual = Math.min(added, budget.remaining);
            if (actual == 0) continue;
            budget.remaining -= actual;
            receipts.add(new Receipt(slot, current.copyWithCount(actual)));
        }
        return receipts;
    }

    private static void collectGroup(Inventory inventory, List<Receipt> receipts, StorageConfig config) {
        var original = CraftingMaterials.copy(inventory);
        var planned = CraftingMaterials.copy(inventory);
        var remaining = new ArrayList<ItemStack>();
        for (var receipt : receipts) remaining.add(planned.getItem(receipt.slot()).split(receipt.stack().getCount()));
        var type = receipts.getFirst().stack();
        double quantity = ManagedJunkBoxes.storedStacks(planned, type, config);
        for (var stack : remaining) quantity += (double) stack.getCount() / stack.getMaxStackSize();

        // Three stacks form a dedicated-box request; smaller unmatched receipts retain the inventory preference.
        if (!config.preferEmptyBoxesOverInventory && config.useMatchingBoxes && quantity >= ManagedJunkBoxes.DEDICATED_STACKS) {
            var dedicated = CraftingMaterials.copy(planned);
            boolean complete = true;
            for (var input : remaining) {
                var copy = input.copy();
                ShulkerStorage.store(dedicated, copy, config, false, null);
                if (!copy.isEmpty()) { complete = false; break; }
            }
            if (complete && ManagedJunkBoxes.storedStacks(dedicated, type, config) == 0) {
                commitIfUnchanged(original, dedicated, inventory);
                return;
            }
        }

        for (int i = 0; i < remaining.size(); i++) {
            var input = remaining.get(i);
            if (config.preferEmptyBoxesOverInventory) ShulkerStorage.store(planned, input, config, false, null);
            else ShulkerStorage.storeMatching(planned, input, config);
            if (input.isEmpty()) continue;
            int slot = receipts.get(i).slot();
            var destination = planned.getItem(slot);
            if (destination.isEmpty()) CraftingMaterials.write(planned, slot, input);
            else if (ItemStack.isSameItemSameComponents(destination, input)) destination.grow(input.getCount());
            else if (!CraftingMaterials.putBack(planned, input)) return;
        }
        commitIfUnchanged(original, planned, inventory);
    }

    private static void commitIfUnchanged(Container original, Container planned, Inventory inventory) {
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++)
            if (!ItemStack.matches(original.getItem(slot), inventory.getItem(slot))) return;
        CraftingMaterials.commitChanges(original, planned, inventory);
    }

    public static Container snapshot(Inventory inventory) { return CraftingMaterials.copy(inventory); }

    static int receivedQuantity(Container inventory, Container before, int slot) {
        var current = inventory.getItem(slot);
        var old = before.getItem(slot);
        int added = current.getCount() - (ItemStack.isSameItemSameComponents(current, old) ? old.getCount() : 0);
        return Math.max(0, Math.min(added, countLoose(inventory, current) - countLoose(before, current)));
    }

    private static int countLoose(Container inventory, ItemStack wanted) {
        int count = 0;
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            var stack = inventory.getItem(slot);
            if (ItemStack.isSameItemSameComponents(stack, wanted)) count += stack.getCount();
        }
        return count;
    }
}
