package dev.magicshulkerboxes;

import com.mojang.authlib.GameProfile;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/** Verifies that Carpet actions belong to GCA, while MSB still loads alongside both mods. */
public class CarpetCompatibilityGameTests {
    @GameTest public void msbDoesNotRefillCarpetHands(GameTestHelper helper) throws Exception {
        if (!FabricLoader.getInstance().isModLoaded("carpet")) { helper.succeed(); return; }
        withGcaRefill(false, () -> {
            var player = fake(helper);
            player.getInventory().setItem(0, new ItemStack(Items.SNOWBALL));
            player.getInventory().setItem(9, box(new ItemStack(Items.SNOWBALL, 4)));
            useContinuously(player);
            player.tick();
            check(helper, player.getMainHandItem().isEmpty(), "MSB does not replenish Carpet hands when GCA is disabled");
            check(helper, contents(player, 9) == 4, "MSB leaves the fake player's box untouched");
        });
        helper.succeed();
    }

    @GameTest public void gcaAlonePlacesAnvilsAcrossPartiallyUsedAndSecondBox(GameTestHelper helper) throws Exception {
        if (!FabricLoader.getInstance().isModLoaded("gca")) { helper.succeed(); return; }
        withGcaRefill(true, () -> {
            var player = fake(helper);
            var position = helper.absolutePos(new BlockPos(1, 2, 1));
            helper.setBlock(1, 1, 3, Blocks.STONE);
            player.getInventory().setItem(0, new ItemStack(Items.ANVIL));
            var firstContents = net.minecraft.core.NonNullList.withSize(27, ItemStack.EMPTY);
            firstContents.set(25, new ItemStack(Items.ANVIL, 2));
            var firstBox = box(ItemStack.EMPTY);
            firstBox.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(firstContents));
            player.getInventory().setItem(9, firstBox);
            player.getInventory().setItem(10, box(new ItemStack(Items.ANVIL, 4)));
            useContinuously(player);
            int placed = 0;
            for (int tick = 0; tick < 100; tick++) {
                player.snapTo(position.getX() + 0.5, position.getY(), position.getZ() + 0.5, 0, 45);
                player.tick();
                if (helper.getBlockState(new BlockPos(1, 2, 3)).is(Blocks.ANVIL)) {
                    placed++;
                    // Reuse the placement face without opening the newly placed anvil's menu.
                    helper.setBlock(1, 2, 3, Blocks.AIR);
                }
            }
            check(helper, placed == 7, "GCA alone places all seven anvils across two boxes; placed=" + placed);
            check(helper, contents(player, 9) == 0 && contents(player, 10) == 0 && player.getMainHandItem().isEmpty(),
                    "Both sources are exhausted exactly once");
        });
        helper.succeed();
    }

    private interface Scenario { void run() throws Exception; }

    private static void withGcaRefill(boolean enabled, Scenario scenario) throws Exception {
        if (!FabricLoader.getInstance().isModLoaded("gca")) { scenario.run(); return; }
        var settings = Class.forName("dev.dubhe.gugle.carpet.GcaSetting");
        var refill = settings.getField("fakePlayerAutoReplenishment");
        var boxes = settings.getField("fakePlayerAutoReplenishmentFormShulkerBox");
        boolean oldRefill = refill.getBoolean(null), oldBoxes = boxes.getBoolean(null);
        try {
            refill.setBoolean(null, enabled);
            boxes.setBoolean(null, enabled);
            scenario.run();
        } finally {
            refill.setBoolean(null, oldRefill);
            boxes.setBoolean(null, oldBoxes);
        }
    }

    private static ServerPlayer fake(GameTestHelper helper) throws Exception {
        var server = helper.getLevel().getServer();
        var profile = new GameProfile(UUID.randomUUID(), "MSBCompatibility");
        var information = ClientInformation.createDefault();
        var player = (ServerPlayer) Class.forName("carpet.patches.EntityPlayerMPFake")
                .getMethod("respawnFake", MinecraftServer.class, ServerLevel.class, GameProfile.class, ClientInformation.class)
                .invoke(null, server, helper.getLevel(), profile, information);
        var connection = (Connection) Class.forName("carpet.patches.FakeClientConnection")
                .getConstructor(PacketFlow.class).newInstance(PacketFlow.SERVERBOUND);
        player.connection = new ServerGamePacketListenerImpl(server, connection, player,
                new CommonListenerCookie(profile, 0, information, false));
        player.setGameMode(GameType.SURVIVAL);
        var position = helper.absolutePos(new BlockPos(1, 2, 1));
        player.snapTo(position.getX(), position.getY(), position.getZ(), 0, -90);
        return player;
    }

    private static void useContinuously(ServerPlayer player) throws Exception {
        var pack = Class.forName("carpet.fakes.ServerPlayerInterface").getMethod("getActionPack").invoke(player);
        var type = Class.forName("carpet.helpers.EntityPlayerActionPack$ActionType");
        var action = Class.forName("carpet.helpers.EntityPlayerActionPack$Action");
        pack.getClass().getMethod("start", type, action).invoke(pack, type.getField("USE").get(null),
                action.getMethod("continuous").invoke(null));
    }

    private static ItemStack box(ItemStack contents) {
        var box = new ItemStack(Items.SHULKER_BOX);
        box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(contents)));
        return box;
    }
    private static int contents(ServerPlayer player, int slot) {
        return player.getInventory().getItem(slot).get(DataComponents.CONTAINER).stream().mapToInt(ItemStack::getCount).sum();
    }
    private static void check(GameTestHelper helper, boolean condition, String reason) {
        helper.assertTrue(condition, Component.literal(reason));
    }
}
