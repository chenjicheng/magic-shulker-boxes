package dev.magicshulkerboxes.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.magicshulkerboxes.ManagedJunkBoxes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(Inventory.class)
abstract class ManagedJunkInventoryMixin {
    @WrapMethod(method = "removeItem(II)Lnet/minecraft/world/item/ItemStack;")
    private ItemStack msb$clearRemovedRole(int slot, int count, Operation<ItemStack> original) {
        return ManagedJunkBoxes.removedFrom((Inventory) (Object) this, original.call(slot, count));
    }

    @WrapMethod(method = "removeItemNoUpdate")
    private ItemStack msb$clearRemovedStackRole(int slot, Operation<ItemStack> original) {
        return ManagedJunkBoxes.removedFrom((Inventory) (Object) this, original.call(slot));
    }

    @WrapMethod(method = "setItem")
    private void msb$clearEquipmentRole(int slot, ItemStack stack, Operation<Void> original) {
        original.call(slot, slot < Inventory.INVENTORY_SIZE ? stack : ManagedJunkBoxes.outsideCopy(stack));
    }
}
