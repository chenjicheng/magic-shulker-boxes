package dev.magicshulkerboxes;

import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;

/** Server-only hand refilling, scoped to a Carpet fake player's complete tick. */
public final class FakePlayerRefill {
    private FakePlayerRefill() {}
    private static final ThreadLocal<Observation> ACTIVE = new ThreadLocal<>();

    private static final class Observation {
        final ServerPlayer player;
        final int selected;
        final ItemStack mainReference, offhandReference;
        final ItemStack mainBefore, offhandBefore;
        boolean dropped;

        Observation(ServerPlayer player) {
            this.player = player;
            selected = player.getInventory().getSelectedSlot();
            mainReference = player.getMainHandItem();
            offhandReference = player.getOffhandItem();
            mainBefore = mainReference.copy();
            offhandBefore = offhandReference.copy();
        }
    }

    /** Includes both Carpet actions in ServerPlayer.tick and delayed item consumption in doTick. */
    public static void tick(ServerPlayer player, Runnable original) {
        if (!eligible(player) || !MagicShulkerBoxes.configFor(player).carpetRefill) { original.run(); return; }
        var observation = new Observation(player);
        var previous = ACTIVE.get();
        ACTIVE.set(observation);
        try {
            original.run();
            if (observation.dropped || !eligible(player)) return;
            var config = MagicShulkerBoxes.configFor(player);
            var inventory = player.getInventory();
            int moved = 0;
            // Moving or swapping a hand leaves its original stack intact. Only an exhausted reference triggers refill.
            if (inventory.getSelectedSlot() == observation.selected && observation.mainReference.isEmpty()) {
                moved += restock(inventory, observation.selected, observation.mainBefore, config);
            }
            if (observation.offhandReference.isEmpty()) {
                moved += restock(inventory, Inventory.SLOT_OFFHAND, observation.offhandBefore, config);
            }
            if (moved > 0) player.inventoryMenu.broadcastChanges();
        } finally {
            if (previous == null) ACTIVE.remove(); else ACTIVE.set(previous);
        }
    }

    /** Explicit Carpet drop actions must not immediately pull more items out of the boxes. */
    public static void dropping(ServerPlayer player) {
        var observation = ACTIVE.get();
        if (observation != null && observation.player == player) observation.dropped = true;
    }

    private static boolean eligible(ServerPlayer player) {
        var mode = player.gameMode.getGameModeForPlayer();
        return player.isAlive() && (mode == GameType.SURVIVAL || mode == GameType.ADVENTURE)
                && player.containerMenu == player.inventoryMenu && player.inventoryMenu.getCarried().isEmpty();
    }

    /** Plan the replacement and all use remainders on copies; a failed attempt leaves every slot untouched. */
    static int restock(Container inventory, int targetSlot, ItemStack previous, StorageConfig config) {
        if (!config.carpetRefill || previous.isEmpty() || !previous.getItem().canFitInsideContainerItems()
                || !((targetSlot >= 0 && targetSlot < 9) || targetSlot == Inventory.SLOT_OFFHAND)
                || targetSlot >= inventory.getContainerSize()) return 0;
        var current = inventory.getItem(targetSlot);
        var remainder = previous.get(DataComponents.USE_REMAINDER);
        if (!current.isEmpty() && (remainder == null || !ItemStack.isSameItemSameComponents(current, remainder.convertInto())
                || current.getCount() != remainder.convertInto().getCount())) return 0;

        var original = CraftingMaterials.copy(inventory);
        var planned = CraftingMaterials.copy(inventory);
        // Reserve the hand while choosing a split slot: the modified box must never be overwritten by its contents.
        CraftingMaterials.write(planned, targetSlot, previous.copyWithCount(1));
        int count = config.refillFullStack ? previous.getMaxStackSize() : 1;
        ItemStack replacement = ItemStack.EMPTY;
        int sourceBox = -1;
        // As with client refilling, loose backpack supplies take priority over opening a box.
        for (int slot = 9; slot < Math.min(36, planned.getContainerSize()); slot++) {
            var candidate = planned.getItem(slot);
            if (!matches(previous, candidate)) continue;
            int amount = Math.min(count, candidate.getCount());
            replacement = candidate.copyWithCount(amount);
            candidate.shrink(amount);
            break;
        }
        if (replacement.isEmpty()) {
            var taken = CraftingMaterials.takeBox(planned, stack -> matches(previous, stack), count, config, box -> true);
            if (taken == null) return 0;
            replacement = taken.stack();
            sourceBox = taken.boxSlot();
        }
        CraftingMaterials.write(planned, targetSlot, replacement);
        if (!current.isEmpty() && !CraftingMaterials.keepRemainder(planned, current, sourceBox, config)) return 0;
        CraftingMaterials.commitChanges(original, planned, inventory);
        return replacement.getCount();
    }

    private static boolean matches(ItemStack previous, ItemStack candidate) {
        if (candidate.isEmpty() || !ItemStack.isSameItem(previous, candidate)) return false;
        if (!previous.isDamageableItem()) return ItemStack.isSameItemSameComponents(previous, candidate);
        if (!candidate.isDamageableItem() || candidate.getDamageValue() >= candidate.getMaxDamage()) return false;
        var expected = previous.copy();
        expected.setDamageValue(candidate.getDamageValue());
        return ItemStack.isSameItemSameComponents(expected, candidate);
    }
}
