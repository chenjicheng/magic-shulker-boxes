package dev.magicshulkerboxes.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.magicshulkerboxes.MagicShulkerBoxes;
import dev.magicshulkerboxes.ShulkerStorage;
import dev.magicshulkerboxes.PickupRelocation;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ItemEntity.class)
abstract class ItemEntityMixin {
    // This call is after vanilla's server-side, pickup-delay and owner checks. Leave those checks intact.
    @WrapOperation(method = "playerTouch", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Inventory;add(Lnet/minecraft/world/item/ItemStack;)Z"))
    private boolean magicShulkerBoxes$storePickup(Inventory inventory, ItemStack incoming, Operation<Boolean> original) {
        var config = inventory.player instanceof ServerPlayer player
                ? MagicShulkerBoxes.configFor(player) : MagicShulkerBoxes.config();
        if (!config.pickupStorageEnabled) return original.call(inventory, incoming);
        int relocated = PickupRelocation.collectReserved(inventory, incoming);
        if (relocated >= 0) return incoming.isEmpty() || original.call(inventory, incoming) || relocated > 0;
        var source = (ItemEntity) (Object) this;
        boolean allowMakingSpace = !source.getTags().contains(PickupRelocation.RELOCATED_TAG);
        var dropHandler = PickupRelocation.handler(inventory);

        int stored = 0;
        if (!config.onlyWhenInventoryFull) {
            stored = ShulkerStorage.store(inventory, incoming, config, allowMakingSpace, dropHandler);
            if (incoming.isEmpty()) return true;
        }

        boolean vanillaAccepted = original.call(inventory, incoming);
        if (config.onlyWhenInventoryFull) {
            stored = ShulkerStorage.store(inventory, incoming, config, allowMakingSpace, dropHandler);
        }
        // Vanilla remains responsible for pickup animation, statistics, and removing an exhausted item entity.
        return vanillaAccepted || stored > 0;
    }
}
