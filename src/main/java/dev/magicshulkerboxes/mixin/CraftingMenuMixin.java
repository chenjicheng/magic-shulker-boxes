package dev.magicshulkerboxes.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.magicshulkerboxes.CraftingRecipeSources;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.RecipeBookMenu.PostPlaceAction;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(AbstractCraftingMenu.class)
public abstract class CraftingMenuMixin {
    @WrapMethod(method = "handlePlacement")
    private PostPlaceAction msb$placeWithBoxSources(boolean all, boolean creative, RecipeHolder<?> recipe,
                                                   ServerLevel level, Inventory inventory, Operation<PostPlaceAction> original) {
        return CraftingRecipeSources.place((AbstractCraftingMenu) (Object) this, inventory, recipe, level, all,
                () -> original.call(all, creative, recipe, level, inventory));
    }
}
