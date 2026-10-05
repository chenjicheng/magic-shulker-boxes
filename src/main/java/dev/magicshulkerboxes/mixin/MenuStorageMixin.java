package dev.magicshulkerboxes.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.magicshulkerboxes.MagicShulkerBoxes;
import dev.magicshulkerboxes.MenuStorage;
import dev.magicshulkerboxes.ShulkerStorage;
import dev.magicshulkerboxes.StorageFailure;
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
    @Unique private boolean msb$collecting;

    @WrapMethod(method = "clicked")
    private void msb$collectAfterClick(int index, int button, ClickType type, Player player, Operation<Void> original) {
        var menu = (AbstractContainerMenu) (Object) this;
        if (!(player instanceof ServerPlayer server) || menu instanceof AbstractCraftingMenu
                || player.containerMenu != menu || !menu.stillValid(player) || msb$collecting
                || !MenuStorage.canWriteCarriedContainers(server)) {
            original.call(index, button, type, player);
            return;
        }
        var slot = index >= 0 && index < menu.slots.size() ? menu.getSlot(index) : null;
        boolean external = slot != null && slot.container != player.getInventory();
        var cursor = menu.getCarried().copy();
        var source = external ? slot.getItem().copy() : ItemStack.EMPTY;
        boolean deposit = msb$containerCursor
                || (external && (type == ClickType.SWAP || type == ClickType.QUICK_MOVE));
        var before = deposit ? MenuStorage.snapshot(player.getInventory()) : null;
        msb$collecting = true;
        try {
            original.call(index, button, type, player);
            if (deposit && player.containerMenu == menu && menu.stillValid(player))
                MenuStorage.collectDeposited(player.getInventory(), before, MagicShulkerBoxes.configFor(server));
            if (external && type == ClickType.QUICK_MOVE && !source.isEmpty()
                    && MagicShulkerBoxes.configFor(server).pickupStorageEnabled
                    && !ShulkerStorage.isShulker(source) && source.getItem().canFitInsideContainerItems()
                    && player.containerMenu == menu && slot.mayPickup(player)
                    && !slot.getItem().isEmpty() && ItemStack.isSameItemSameComponents(slot.getItem(), source))
                StorageFailure.noSpace(server);
        } finally {
            msb$collecting = false;
        }
        var carried = menu.getCarried();
        if (carried.isEmpty()) msb$containerCursor = false;
        else if (external && type == ClickType.PICKUP && !source.isEmpty()
                && ItemStack.isSameItemSameComponents(source, carried)
                && (cursor.isEmpty() || !ItemStack.isSameItemSameComponents(cursor, carried)
                    || carried.getCount() > cursor.getCount())) msb$containerCursor = true;
        else if (!cursor.isEmpty() && !ItemStack.isSameItemSameComponents(cursor, carried))
            msb$containerCursor = false;
    }
}
