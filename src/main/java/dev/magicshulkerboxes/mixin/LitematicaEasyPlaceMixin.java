package dev.magicshulkerboxes.mixin;

import dev.magicshulkerboxes.client.SchematicRefillClient;
import net.minecraft.world.InteractionResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "fi.dy.masa.litematica.util.EasyPlaceUtils", remap = false)
public abstract class LitematicaEasyPlaceMixin {
    @Inject(method = "handleEasyPlace", at = @At("HEAD"), remap = false)
    private static void msb$begin(CallbackInfoReturnable<InteractionResult> ci) { SchematicRefillClient.begin(); }
    @Inject(method = "handleEasyPlace", at = @At("RETURN"), cancellable = true, remap = false)
    private static void msb$end(CallbackInfoReturnable<InteractionResult> ci) {
        if (SchematicRefillClient.end()) ci.setReturnValue(InteractionResult.SUCCESS);
    }
}
