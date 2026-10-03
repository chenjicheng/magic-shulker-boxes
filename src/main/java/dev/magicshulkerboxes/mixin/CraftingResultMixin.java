package dev.magicshulkerboxes.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.magicshulkerboxes.CraftingRecipeSources;
import dev.magicshulkerboxes.CraftingRefill;
import dev.magicshulkerboxes.MagicShulkerBoxes;
import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(ResultSlot.class)
public abstract class CraftingResultMixin {
    @Shadow @Final private CraftingContainer craftSlots;
    @WrapMethod(method = "onTake")
    private void msb$refillAfterCraft(Player player, ItemStack crafted, Operation<Void> original) {
        // Shift-click passes the emptied original result stack; the valid input recipe is the authority here.
        if (!(player instanceof ServerPlayer serverPlayer)
                || !(player.containerMenu instanceof AbstractCraftingMenu menu) || !CraftingRecipeSources.allowed(serverPlayer, menu)) {
            original.call(player, crafted); return;
        }
        var positioned = craftSlots.asPositionedCraftInput();
        var recipe = serverPlayer.level().recipeAccess().getRecipeFor(RecipeType.CRAFTING, positioned.input(), serverPlayer.level());
        if (recipe.isEmpty()) { original.call(player, crafted); return; }
        var template = craftSlots.getItems().stream().map(ItemStack::copy).toList();
        var leftovers = recipe.get().value().getRemainingItems(positioned.input());
        var remainders = NonNullList.withSize(template.size(), ItemStack.EMPTY);
        for (int y = 0; y < positioned.input().height(); y++) for (int x = 0; x < positioned.input().width(); x++) {
            remainders.set(x + positioned.left() + (y + positioned.top()) * craftSlots.getWidth(),
                    leftovers.get(x + y * positioned.input().width()).copy());
        }
        original.call(player, crafted);
        if (CraftingRecipeSources.allowed(serverPlayer, menu)) {
            var config = MagicShulkerBoxes.configFor(serverPlayer);
            CraftingRefill.refill(dev.magicshulkerboxes.RefillSources.of(player, config), craftSlots, template, remainders, config);
        }
    }
}
