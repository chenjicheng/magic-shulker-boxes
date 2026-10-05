package dev.magicshulkerboxes.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.magicshulkerboxes.MagicShulkerBoxes;
import dev.magicshulkerboxes.MenuStorage;
import dev.magicshulkerboxes.PickupStorage;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ItemEntity.class)
abstract class ItemEntityMixin {
    @Unique private Capture msb$pickup;
    private record Capture(ServerPlayer player, Container before, ItemStack stack, int remaining, boolean accepted) {}

    @WrapMethod(method = "playerTouch")
    private void msb$afterVanillaPickup(Player player, Operation<Void> original) {
        var previous = msb$pickup;
        msb$pickup = null;
        try {
            original.call(player);
            var capture = msb$pickup;
            if (capture != null) PickupStorage.finish((ItemEntity) (Object) this, capture.player(),
                    capture.before(), capture.stack(), capture.remaining(), capture.accepted());
        } finally {
            msb$pickup = previous;
        }
    }

    // Reaching this call proves vanilla accepted the delay and owner checks. Do not alter its result.
    @WrapOperation(method = "playerTouch", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Inventory;add(Lnet/minecraft/world/item/ItemStack;)Z"))
    private boolean msb$observePickup(Inventory inventory, ItemStack incoming, Operation<Boolean> original) {
        if (!(inventory.player instanceof ServerPlayer player)
                || !MagicShulkerBoxes.configFor(player).pickupStorageEnabled
                || !MenuStorage.canWriteCarriedContainers(player)) return original.call(inventory, incoming);
        var before = MenuStorage.snapshot(inventory);
        boolean accepted = original.call(inventory, incoming);
        msb$pickup = new Capture(player, before, incoming, incoming.getCount(), accepted);
        return accepted;
    }
}
