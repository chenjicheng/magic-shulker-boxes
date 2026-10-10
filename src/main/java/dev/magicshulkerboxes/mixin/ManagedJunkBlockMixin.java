package dev.magicshulkerboxes.mixin;

import dev.magicshulkerboxes.ManagedJunkBoxes;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(BlockItem.class)
abstract class ManagedJunkBlockMixin {
    @ModifyArg(method = "updateBlockEntityComponents", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/entity/BlockEntity;applyComponentsFromItemStack(Lnet/minecraft/world/item/ItemStack;)V"), index = 0)
    private static ItemStack msb$placeWithoutJunkRole(ItemStack stack) { return ManagedJunkBoxes.outsideCopy(stack); }
}
