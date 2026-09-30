package dev.magicshulkerboxes.mixin;

import dev.magicshulkerboxes.client.CraftingRefillClient;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.StackedItemContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Inventory.class)
public abstract class CraftingInventoryClientMixin {
    @Inject(method = "fillStackedContents", at = @At("TAIL"))
    private void msb$recipeBookBoxContents(StackedItemContents contents, CallbackInfo ci) {
        CraftingRefillClient.account((Inventory) (Object) this, contents);
    }
}
