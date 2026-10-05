package dev.magicshulkerboxes;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

/** Container-storage tests use a real world owner; SimpleContainer does not establish ownership. */
final class TestContainers {
    private TestContainers() {}

    static ChestBlockEntity chest(GameTestHelper helper, ServerPlayer player) {
        return chest(player, helper.absolutePos(new BlockPos(1, 0, 1)));
    }

    static ChestBlockEntity chest(ServerPlayer player) {
        return chest(player, player.blockPosition().offset(1, 0, 1));
    }

    private static ChestBlockEntity chest(ServerPlayer player, BlockPos position) {
        player.level().setBlock(position, Blocks.AIR.defaultBlockState(), 3);
        player.level().setBlock(position, Blocks.CHEST.defaultBlockState(), 3);
        player.setPos(position.getX() + 0.5, position.getY() + 1, position.getZ() + 0.5);
        var chest = (ChestBlockEntity) player.level().getBlockEntity(position);
        if (chest == null) throw new AssertionError("World-owned chest was not created");
        return chest;
    }
}
