package dev.magicshulkerboxes;

import com.mojang.authlib.GameProfile;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
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
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.GameType;

/** Runs the published Carpet fake-player tick and action pack, without a client or profile lookup. */
public class FakePlayerRefillGameTests {
    @GameTest public void carpetFakeCanKeepUsingAfterLastSnowball(GameTestHelper helper) throws Exception {
        if (!FabricLoader.getInstance().isModLoaded("carpet")) { helper.succeed(); return; }
        var player = fake(helper);
        player.getInventory().setItem(0, new ItemStack(Items.SNOWBALL));
        player.getInventory().setItem(9, box(new ItemStack(Items.SNOWBALL, 4)));
        useContinuously(player);
        player.tick();
        check(helper, player.getMainHandItem().is(Items.SNOWBALL), "Fake must refill its exhausted hand without IPN");
        check(helper, player.getMainHandItem().getCount() == 4, "Exactly the source stack reaches the hand");
        check(helper, stored(player).isEmpty(), "Source stack is removed exactly once");
        for (int tick = 0; tick < 6; tick++) player.tick();
        check(helper, player.getMainHandItem().getCount() < 4, "Carpet continues using the refilled stack");
        helper.succeed();
    }

    @GameTest public void carpetFakeRefillsOffhandWithoutClient(GameTestHelper helper) throws Exception {
        if (!FabricLoader.getInstance().isModLoaded("carpet")) { helper.succeed(); return; }
        var player = fake(helper);
        player.getInventory().setItem(40, new ItemStack(Items.SNOWBALL));
        player.getInventory().setItem(9, box(new ItemStack(Items.SNOWBALL, 4)));
        useContinuously(player);
        player.tick();
        check(helper, player.getOffhandItem().is(Items.SNOWBALL) && player.getOffhandItem().getCount() == 4,
                "Fake refills the actual offhand after Carpet uses its last item");
        check(helper, player.getMainHandItem().isEmpty() && stored(player).isEmpty(), "No main-hand replacement or duplicate supply");
        helper.succeed();
    }

    @GameTest public void carpetDrinkingPreservesBottleAndRefillsPotion(GameTestHelper helper) throws Exception {
        if (!FabricLoader.getInstance().isModLoaded("carpet")) { helper.succeed(); return; }
        var player = fake(helper);
        player.getInventory().setItem(0, new ItemStack(Items.POTION));
        player.getInventory().setItem(9, box(new ItemStack(Items.POTION)));
        useContinuously(player);
        for (int tick = 0; tick < 34; tick++) player.tick();
        check(helper, player.getMainHandItem().is(Items.POTION), "Delayed drinking completion refills a potion");
        check(helper, stored(player).isEmpty(), "One spare potion is extracted");
        int bottles = 0;
        for (int slot = 0; slot < 36; slot++) {
            var stack = player.getInventory().getItem(slot);
            if (stack.is(Items.GLASS_BOTTLE)) bottles += stack.getCount();
        }
        check(helper, bottles == 1, "The consumed potion's bottle is retained exactly once");
        helper.succeed();
    }

    @GameTest public void carpetDropAndHandSwapDoNotRefill(GameTestHelper helper) throws Exception {
        if (!FabricLoader.getInstance().isModLoaded("carpet")) { helper.succeed(); return; }
        var dropped = fake(helper);
        dropped.getInventory().setItem(40, new ItemStack(Items.SNOWBALL));
        dropped.getInventory().setItem(9, box(new ItemStack(Items.SNOWBALL, 4)));
        // Carpet's offhand removal shrinks the same stack reference; explicit drop suppression must still apply.
        FakePlayerRefill.tick(dropped, () -> {
            try {
                actionPack(dropped).getClass().getMethod("drop", int.class, boolean.class).invoke(actionPack(dropped), 40, true);
            } catch (ReflectiveOperationException exception) { throw new RuntimeException(exception); }
        });
        check(helper, dropped.getOffhandItem().isEmpty() && stored(dropped).getFirst().getCount() == 4,
                "Explicit offhand drop does not drain box supplies");
        var swapped = fake(helper);
        swapped.getInventory().setItem(0, new ItemStack(Items.SNOWBALL));
        swapped.getInventory().setItem(9, box(new ItemStack(Items.SNOWBALL, 4)));
        startAction(swapped, "SWAP_HANDS", "once");
        swapped.tick();
        check(helper, swapped.getMainHandItem().isEmpty() && swapped.getOffhandItem().is(Items.SNOWBALL), "Swap is preserved");
        check(helper, stored(swapped).getFirst().getCount() == 4, "Swapping does not extract another item");
        helper.succeed();
    }

    @GameTest public void brokenToolRefillsButChangingSelectedSlotCancels(GameTestHelper helper) throws Exception {
        if (!FabricLoader.getInstance().isModLoaded("carpet")) { helper.succeed(); return; }
        var player = fake(helper);
        var tool = new ItemStack(Items.DIAMOND_PICKAXE); tool.setDamageValue(tool.getMaxDamage() - 1);
        var spare = new ItemStack(Items.DIAMOND_PICKAXE); spare.setDamageValue(15);
        player.getInventory().setItem(0, tool);
        player.getInventory().setItem(9, box(spare.copy()));
        FakePlayerRefill.tick(player, () -> tool.hurtAndBreak(1, player, InteractionHand.MAIN_HAND));
        check(helper, ItemStack.matches(player.getMainHandItem(), spare), "A genuinely broken tool gets its same-component spare");
        check(helper, stored(player).isEmpty(), "One spare tool was extracted");
        var switched = fake(helper);
        var snowball = new ItemStack(Items.SNOWBALL); switched.getInventory().setItem(0, snowball);
        switched.getInventory().setItem(9, box(new ItemStack(Items.SNOWBALL, 4)));
        FakePlayerRefill.tick(switched, () -> { snowball.shrink(1); switched.getInventory().setSelectedSlot(1); });
        check(helper, stored(switched).getFirst().getCount() == 4, "Changing the monitored hotbar slot cancels replenishment");
        helper.succeed();
    }

    @GameTest public void fakePlayerSettingsAndUnsafeStatesApplyOnServer(GameTestHelper helper) throws Exception {
        if (!FabricLoader.getInstance().isModLoaded("carpet")) { helper.succeed(); return; }
        var config = MagicShulkerBoxes.config();
        boolean oldRefill = config.carpetRefill, oldAllowed = config.allowPlayerSettings;
        try {
            config.carpetRefill = false; config.allowPlayerSettings = false;
            var disabled = snowballFake(helper); disabled.tick();
            check(helper, disabled.getMainHandItem().isEmpty() && stored(disabled).getFirst().getCount() == 4,
                    "Server switch prevents fake-player replenishment");
            config.allowPlayerSettings = true;
            var optedIn = snowballFake(helper);
            MagicShulkerBoxes.players(helper.getLevel().getServer()).save(optedIn.getUUID(), ConfigFile.parsePreferences("{\"carpetRefill\":true}"));
            optedIn.tick();
            check(helper, optedIn.getMainHandItem().getCount() == 4 && stored(optedIn).isEmpty(), "Fake UUID personal override works");
            config.allowPlayerSettings = false; config.carpetRefill = true;
            var cursor = snowballFake(helper); cursor.inventoryMenu.setCarried(new ItemStack(Items.DIRT)); cursor.tick();
            check(helper, stored(cursor).getFirst().getCount() == 4, "Cursor item prevents refill");
            var creative = snowballFake(helper); creative.setGameMode(GameType.CREATIVE); creative.tick();
            check(helper, stored(creative).getFirst().getCount() == 4, "Creative never extracts from boxes");
            var menu = snowballFake(helper);
            menu.containerMenu = new net.minecraft.world.inventory.ChestMenu(net.minecraft.world.inventory.MenuType.GENERIC_9x3,
                    1, menu.getInventory(), new net.minecraft.world.SimpleContainer(27), 3);
            menu.tick();
            check(helper, stored(menu).getFirst().getCount() == 4, "An open container prevents refill");
        } finally { config.carpetRefill = oldRefill; config.allowPlayerSettings = oldAllowed; }
        helper.succeed();
    }

    private static ServerPlayer snowballFake(GameTestHelper helper) throws Exception {
        var player = fake(helper);
        player.getInventory().setItem(0, new ItemStack(Items.SNOWBALL));
        player.getInventory().setItem(9, box(new ItemStack(Items.SNOWBALL, 4)));
        useContinuously(player);
        return player;
    }

    private static ServerPlayer fake(GameTestHelper helper) throws Exception {
        var server = helper.getLevel().getServer();
        var profile = new GameProfile(UUID.randomUUID(), "MSBRefillTest");
        var information = ClientInformation.createDefault();
        var player = (ServerPlayer) Class.forName("carpet.patches.EntityPlayerMPFake")
                .getMethod("respawnFake", MinecraftServer.class, ServerLevel.class, GameProfile.class, ClientInformation.class)
                .invoke(null, server, helper.getLevel(), profile, information);
        var connection = (Connection) Class.forName("carpet.patches.FakeClientConnection")
                .getConstructor(PacketFlow.class).newInstance(PacketFlow.SERVERBOUND);
        player.connection = new ServerGamePacketListenerImpl(server, connection, player,
                new CommonListenerCookie(profile, 0, information, false));
        player.setGameMode(GameType.SURVIVAL);
        var position = helper.absolutePos(new net.minecraft.core.BlockPos(1, 2, 1));
        player.snapTo(position.getX(), position.getY(), position.getZ(), 0, -90);
        return player;
    }

    private static void useContinuously(ServerPlayer player) throws Exception {
        startAction(player, "USE", "continuous");
    }
    private static Object actionPack(ServerPlayer player) throws ReflectiveOperationException {
        return Class.forName("carpet.fakes.ServerPlayerInterface").getMethod("getActionPack").invoke(player);
    }
    private static void startAction(ServerPlayer player, String type, String repetition) throws Exception {
        var actionPack = actionPack(player);
        var actionType = Class.forName("carpet.helpers.EntityPlayerActionPack$ActionType");
        var action = Class.forName("carpet.helpers.EntityPlayerActionPack$Action");
        actionPack.getClass().getMethod("start", actionType, action).invoke(actionPack,
                actionType.getField(type).get(null), action.getMethod(repetition).invoke(null));
    }

    private static ItemStack box(ItemStack contents) {
        var box = new ItemStack(Items.SHULKER_BOX);
        box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(contents)));
        return box;
    }
    private static List<ItemStack> stored(ServerPlayer player) {
        return player.getInventory().getItem(9).get(DataComponents.CONTAINER).stream().filter(stack -> !stack.isEmpty()).toList();
    }
    private static void check(GameTestHelper helper, boolean value, String reason) { helper.assertTrue(value, Component.literal(reason)); }
}
