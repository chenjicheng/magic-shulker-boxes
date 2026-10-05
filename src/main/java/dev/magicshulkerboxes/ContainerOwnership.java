package dev.magicshulkerboxes;

import dev.magicshulkerboxes.mixin.CompoundContainerAccess;
import dev.magicshulkerboxes.mixin.MerchantContainerAccess;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.MerchantContainer;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Different Container objects do not imply different storage: a menu may edit a carried item. */
public final class ContainerOwnership {
    private ContainerOwnership() {}

    public static boolean independent(Container container, ServerPlayer player) {
        if (container == player.getEnderChestInventory()) return true;
        if (container instanceof BlockEntity block) {
            return block.getLevel() == player.level() && !block.isRemoved()
                    && player.level().getBlockEntity(block.getBlockPos()) == block;
        }
        if (container instanceof Entity entity) return present(entity, player);
        if (container instanceof CompoundContainer) {
            var parts = (CompoundContainerAccess) container;
            return independent(parts.msb$first(), player) && independent(parts.msb$second(), player);
        }
        if (container instanceof MerchantContainer) {
            var merchant = ((MerchantContainerAccess) container).msb$merchant();
            return merchant instanceof Entity entity && present(entity, player)
                    && merchant.getTradingPlayer() == player;
        }
        // These exact vanilla buffers are owned by the crafting menu, never by a carried item.
        return container.getClass() == TransientCraftingContainer.class
                || container.getClass() == ResultContainer.class;
    }

    private static boolean present(Entity entity, ServerPlayer player) {
        return entity.level() == player.level() && !entity.isRemoved()
                && player.level().getEntity(entity.getId()) == entity;
    }
}
