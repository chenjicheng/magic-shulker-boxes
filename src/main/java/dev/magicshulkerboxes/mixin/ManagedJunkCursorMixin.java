package dev.magicshulkerboxes.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.magicshulkerboxes.ManagedJunkBoxes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(AbstractContainerMenu.class)
abstract class ManagedJunkCursorMixin {
    @WrapMethod(method = "clicked")
    private void msb$preserveInternalSwaps(int slot, int button, ClickType type, Player player, Operation<Void> original) {
        ManagedJunkBoxes.withinMenu(player.getInventory(), () -> original.call(slot, button, type, player));
    }

    @ModifyVariable(method = "setCarried", at = @At("HEAD"), argsOnly = true)
    private ItemStack msb$clearCursorRole(ItemStack stack) {
        // The own-inventory cursor is temporary backpack ownership; external menus end that ownership.
        return (Object) this instanceof InventoryMenu ? stack : ManagedJunkBoxes.outsideCopy(stack);
    }
}
