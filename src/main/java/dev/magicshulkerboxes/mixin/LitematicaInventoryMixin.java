package dev.magicshulkerboxes.mixin;

import dev.magicshulkerboxes.client.SchematicRefillClient;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "fi.dy.masa.litematica.util.InventoryUtils", remap = false)
public abstract class LitematicaInventoryMixin {
    @Inject(method = "schematicWorldPickBlock", at = @At("HEAD"), cancellable = true, remap = false)
    private static void msb$refill(ItemStack stack, BlockPos pos, Level world, Minecraft mc, CallbackInfo ci) {
        if (SchematicRefillClient.tryRefill(stack, mc)) ci.cancel();
    }
}
