package dev.magicshulkerboxes.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.magicshulkerboxes.MagicShulkerBoxes;
import dev.magicshulkerboxes.MenuStorage;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(AbstractContainerMenu.class)
abstract class MenuStorageMixin {
    @Unique private boolean msb$containerCursor;
    @Unique private boolean msb$quickMove;

    @WrapMethod(method = "moveItemStackTo")
    private boolean msb$collectTransfer(
            ItemStack incoming, int start, int end, boolean reverse, Operation<Boolean> original) {
        if (!msb$quickMove) return original.call(incoming, start, end, reverse);
        return MenuStorage.transfer(
                (AbstractContainerMenu) (Object) this,
                incoming,
                start,
                end,
                reverse,
                () -> original.call(incoming, start, end, reverse));
    }

    @WrapMethod(method = "clicked")
    private void msb$collectDeposit(
            int index, int button, ClickType type, Player player, Operation<Void> original) {
        var menu = (AbstractContainerMenu) (Object) this;
        if (!(player instanceof ServerPlayer server)
                || menu instanceof AbstractCraftingMenu
                || player.containerMenu != menu
                || !menu.stillValid(player)) {
            original.call(index, button, type, player);
            return;
        }
        var slot = index >= 0 && index < menu.slots.size() ? menu.getSlot(index) : null;
        boolean external = slot != null && slot.container != player.getInventory();
        var cursor = menu.getCarried().copy();
        var source = external ? slot.getItem().copy() : ItemStack.EMPTY;
        boolean deposit = msb$containerCursor || (external && type == ClickType.SWAP);
        var before = deposit ? MenuStorage.snapshot(player.getInventory()) : null;
        boolean previousQuickMove = msb$quickMove;
        msb$quickMove = type == ClickType.QUICK_MOVE;
        try {
            original.call(index, button, type, player);
        } finally {
            msb$quickMove = previousQuickMove;
        }
        if (deposit && type != ClickType.QUICK_MOVE)
            MenuStorage.collectDeposited(
                    player.getInventory(), before, MagicShulkerBoxes.configFor(server));
        var carried = menu.getCarried();
        if (carried.isEmpty()) msb$containerCursor = false;
        else if (external
                && type == ClickType.PICKUP
                && !source.isEmpty()
                && ItemStack.isSameItemSameComponents(source, carried)
                && (cursor.isEmpty()
                        || !ItemStack.isSameItemSameComponents(cursor, carried)
                        || carried.getCount() > cursor.getCount())) msb$containerCursor = true;
        else if (!cursor.isEmpty() && !ItemStack.isSameItemSameComponents(cursor, carried))
            msb$containerCursor = false;
    }
}
