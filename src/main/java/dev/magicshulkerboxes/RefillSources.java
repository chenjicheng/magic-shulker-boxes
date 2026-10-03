package dev.magicshulkerboxes;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Slots 100..126 name the current player's ender storage; -1 as content slot means a direct item.
 */
public final class RefillSources {
    public static final int ENDER_START = 100, ENDER_SIZE = 27;

    private RefillSources() {}

    public static Container of(Player player, StorageConfig config) {
        return config.enderChestRefill
                ? new View(player.getInventory(), player.getEnderChestInventory())
                : player.getInventory();
    }

    public static boolean isEnder(Container inventory, int slot) {
        return inventory instanceof View && slot >= ENDER_START && slot < ENDER_START + ENDER_SIZE;
    }

    public static int freeBoxSlot(Container inventory, int source) {
        if (!isEnder(inventory, source)) return CraftingMaterials.freeSlot(inventory);
        for (int i = ENDER_START; i < inventory.getContainerSize(); i++)
            if (inventory.getItem(i).isEmpty()) return i;
        return -1;
    }

    public static ItemStack item(Container inventory, int outer, int inner) {
        if (outer < 0 || outer >= inventory.getContainerSize()) return ItemStack.EMPTY;
        var source = inventory.getItem(outer);
        if (inner == -1)
            return isEnder(inventory, outer) && !ShulkerStorage.isShulker(source)
                    ? source
                    : ItemStack.EMPTY;
        if (inner < 0 || inner >= 27 || !ShulkerStorage.isShulker(source)) return ItemStack.EMPTY;
        var data =
                source.getOrDefault(
                        net.minecraft.core.component.DataComponents.CONTAINER,
                        net.minecraft.world.item.component.ItemContainerContents.EMPTY);
        if (data.stream().count() > 27) return ItemStack.EMPTY;
        var items = net.minecraft.core.NonNullList.withSize(27, ItemStack.EMPTY);
        data.copyInto(items);
        return items.get(inner);
    }

    public static final class View implements Container {
        final Container inventory, ender;

        public View(Container inventory, Container ender) {
            if (inventory.getContainerSize() <= 40
                    || inventory.getContainerSize() > ENDER_START
                    || ender.getContainerSize() != ENDER_SIZE)
                throw new IllegalArgumentException("Unexpected player storage size");
            this.inventory = inventory;
            this.ender = ender;
        }

        private Container container(int slot) {
            return slot < ENDER_START ? inventory : ender;
        }

        private int index(int slot) {
            return slot < ENDER_START ? slot : slot - ENDER_START;
        }

        @Override
        public int getContainerSize() {
            return ENDER_START + ENDER_SIZE;
        }

        @Override
        public boolean isEmpty() {
            return inventory.isEmpty() && ender.isEmpty();
        }

        private boolean gap(int slot) {
            return slot >= inventory.getContainerSize() && slot < ENDER_START;
        }

        @Override
        public ItemStack getItem(int slot) {
            return gap(slot) ? ItemStack.EMPTY : container(slot).getItem(index(slot));
        }

        @Override
        public ItemStack removeItem(int slot, int count) {
            return gap(slot) ? ItemStack.EMPTY : container(slot).removeItem(index(slot), count);
        }

        @Override
        public ItemStack removeItemNoUpdate(int slot) {
            return gap(slot) ? ItemStack.EMPTY : container(slot).removeItemNoUpdate(index(slot));
        }

        @Override
        public void setItem(int slot, ItemStack stack) {
            if (gap(slot)) {
                if (!stack.isEmpty()) throw new IllegalArgumentException("Reserved source slot");
                return;
            }
            container(slot).setItem(index(slot), stack);
        }

        @Override
        public void setChanged() {
            inventory.setChanged();
            ender.setChanged();
        }

        @Override
        public boolean stillValid(Player player) {
            return inventory.stillValid(player) && ender.stillValid(player);
        }

        @Override
        public void clearContent() {
            inventory.clearContent();
            ender.clearContent();
        }
    }
}
