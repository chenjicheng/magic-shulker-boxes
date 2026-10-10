package dev.magicshulkerboxes.mixin;

import dev.magicshulkerboxes.ManagedJunkBoxes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(Slot.class)
abstract class ManagedJunkSlotMixin {
    @ModifyVariable(method = {"set", "setByPlayer"}, at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private ItemStack msb$clearExternalDestination(ItemStack stack) {
        var slot = (Slot) (Object) this;
        return slot.container instanceof Inventory && slot.getContainerSlot() < Inventory.INVENTORY_SIZE
                ? stack : ManagedJunkBoxes.outsideCopy(stack);
    }
}
