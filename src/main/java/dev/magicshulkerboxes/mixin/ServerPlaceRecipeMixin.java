package dev.magicshulkerboxes.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.magicshulkerboxes.CraftingRecipeSources;
import net.minecraft.core.Holder;
import net.minecraft.recipebook.ServerPlaceRecipe;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ServerPlaceRecipe.class)
public abstract class ServerPlaceRecipeMixin {
    @Shadow @Final private Inventory inventory;
    @Redirect(method = "placeRecipe", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Inventory;fillStackedContents(Lnet/minecraft/world/entity/player/StackedItemContents;)V"))
    private static void msb$accountCarriedBoxes(Inventory inventory, StackedItemContents contents) {
        inventory.fillStackedContents(contents);
        CraftingRecipeSources.account(inventory, contents);
    }
    @WrapMethod(method = "moveItemToGrid")
    private int msb$takeMissingIngredient(Slot slot, Holder<Item> item, int amount, Operation<Integer> original) {
        int remaining = original.call(slot, item, amount);
        return remaining == -1 ? CraftingRecipeSources.takeMissing(inventory, slot, item, amount) : remaining;
    }
}
