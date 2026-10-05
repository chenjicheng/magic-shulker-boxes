package dev.magicshulkerboxes;

import com.mojang.authlib.GameProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

public class StorageFeedbackGameTests {
    @GameTest public void personalSpaceNoticeChoiceObeysItsOwnPermission(GameTestHelper helper) throws Exception {
        var config = MagicShulkerBoxes.config();
        boolean allowed = config.allowPlayerSettings;
        var editable = config.playerEditableSettings;
        var player = full(helper);
        var store = MagicShulkerBoxes.players(helper.getLevel().getServer());
        try {
            config.allowPlayerSettings = true;
            config.playerEditableSettings = List.of("spaceFailureMessages");
            check(helper, SettingsNetwork.accept(player, "{\"spaceFailureMessages\":false}"), "Allowed personal preference is accepted");
            StorageFailure.noSpace(player);
            check(helper, player.notices.isEmpty(), "Personal opt-out hides the shared space notice");
            config.playerEditableSettings = List.of();
            StorageFailure.noSpace(player);
            check(helper, player.notices.size() == 1, "Revoked preference immediately inherits the default");
            check(helper, !store.read(player.getUUID()).get("spaceFailureMessages").getAsBoolean(), "Policy changes preserve the stored choice");
            helper.succeed();
        } finally {
            config.allowPlayerSettings = allowed;
            config.playerEditableSettings = editable;
            store.save(player.getUUID(), new com.google.gson.JsonObject());
        }
    }

    @GameTest public void spaceNoticeSettingCanBeDisabledAndEnabledThroughAdminCommand(GameTestHelper helper) throws Exception {
        var original = ConfigFile.json(MagicShulkerBoxes.config());
        var player = full(helper);
        var commands = helper.getLevel().getServer().getCommands().getDispatcher();
        var source = player.createCommandSourceStack().withPermission(net.minecraft.server.permissions.PermissionSet.ALL_PERMISSIONS);
        try {
            MagicShulkerBoxes.config().pickupStorageEnabled = true;
            MagicShulkerBoxes.config().refillFailureMessages = false;
            check(helper, commands.execute("msb admin set spaceFailureMessages false", source) == 1, "Independent space notice setting is accepted");
            var dropped = new ItemEntity(helper.getLevel(), 0, 4, 0, new ItemStack(Items.COBBLESTONE, 5));
            dropped.setNoPickUpDelay(); dropped.playerTouch(player);
            check(helper, player.notices.isEmpty(), "Disabled space notices stay silent on real pickup");
            check(helper, !dropped.isRemoved() && dropped.getItem().getCount() == 5, "Notice setting does not change item ownership");
            check(helper, commands.execute("msb admin set spaceFailureMessages true", source) == 1, "Space notices can be enabled independently");
            dropped.playerTouch(player);
            check(helper, player.notices.size() == 1 && player.actionBar, "Re-enabling the setting immediately restores the notice");
            check(helper, !MagicShulkerBoxes.config().refillFailureMessages, "Other refill notices retain their setting");
            helper.succeed();
        } finally { MagicShulkerBoxes.replaceConfig(ConfigFile.parseServer(original)); }
    }

    @GameTest public void defaultPickupUsesMatchingBoxBeforeFreeInventory(GameTestHelper helper) {
        withPickup(() -> {
            var player = new RecordingPlayer(helper);
            player.getInventory().setItem(9, box(new ItemStack(Items.STONE, 10)));
            var dropped = new ItemEntity(helper.getLevel(), 0, 4, 0, new ItemStack(Items.STONE, 5));
            dropped.setNoPickUpDelay(); dropped.playerTouch(player);
            check(helper, dropped.isRemoved() && stored(player.getInventory().getItem(9)) == 15, "Matching box wins before free inventory");
            check(helper, player.notices.isEmpty(), "Successful pickup is silent");
        });
        helper.succeed();
    }

    @GameTest public void blockedPickupShowsOneActionBarNoticeEvenWhenRefillNoticesAreOff(GameTestHelper helper) {
        withPickup(() -> {
            var player = full(helper);
            var dropped = new ItemEntity(helper.getLevel(), 0, 4, 0, new ItemStack(Items.COBBLESTONE, 5));
            dropped.setNoPickUpDelay(); dropped.playerTouch(player); dropped.playerTouch(player);
            check(helper, !dropped.isRemoved() && dropped.getItem().getCount() == 5, "Blocked item remains intact");
            check(helper, player.notices.size() == 1 && player.actionBar, "Visible failure is rate limited");
            check(helper, player.notices.getFirst().getString().contains("Not enough space"), "Failure explains the missing storage space");
        });
        helper.succeed();
    }

    @GameTest public void validatedIpnSpaceFailureShowsAnActionBarNotice(GameTestHelper helper) {
        var player = full(helper);
        player.getInventory().setItem(9, box(new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.COBBLESTONE)));
        var source = new ItemStack(Items.COBBLESTONE, 64);
        var target = player.getMainHandItem();
        var request = new RestockNetwork.Request(1, 9, 0, 1, 64, 0, target.getCount(), (1 << 27) - 1,
                ItemFingerprint.of(source, player.registryAccess()), ItemFingerprint.of(target, player.registryAccess()),
                ItemFingerprint.of(player.getInventory().getItem(9), player.registryAccess()));
        check(helper, RestockNetwork.accept(player, request) == 0, "Unsafe extraction is rejected");
        check(helper, player.notices.size() == 1 && player.actionBar, "IPN reports unsafe space");
        check(helper, player.notices.getFirst().getString().contains("Not enough space"), "IPN gives a readable space failure");
        helper.succeed();
    }

    @GameTest public void failedCakeRemainderPlacementShowsAnActionBarNotice(GameTestHelper helper) {
        var player = full(helper);
        player.getInventory().setItem(35, box(new ItemStack(Items.MILK_BUCKET), new ItemStack(Items.MILK_BUCKET),
                new ItemStack(Items.MILK_BUCKET), new ItemStack(Items.MILK_BUCKET), new ItemStack(Items.SUGAR, 2),
                new ItemStack(Items.WHEAT, 3), new ItemStack(Items.EGG)));
        var pos = helper.absolutePos(new net.minecraft.core.BlockPos(1, 0, 1));
        helper.getLevel().setBlock(pos, Blocks.CRAFTING_TABLE.defaultBlockState(), 3);
        player.setPos(pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5);
        var menu = new CraftingMenu(7, player.getInventory(), ContainerLevelAccess.create(helper.getLevel(), pos));
        player.containerMenu = menu;
        var inputs = List.of(Items.MILK_BUCKET, Items.MILK_BUCKET, Items.MILK_BUCKET, Items.SUGAR, Items.EGG,
                Items.SUGAR, Items.WHEAT, Items.WHEAT, Items.WHEAT);
        for (int i = 0; i < 9; i++) menu.getInputGridSlots().get(i).set(new ItemStack(inputs.get(i)));
        menu.clicked(0, 0, ClickType.PICKUP, player);
        check(helper, menu.getCarried().is(Items.CAKE), "Original craft completes");
        check(helper, player.notices.size() == 1 && player.actionBar, "Failed remainder placement is explicit");
        check(helper, player.notices.getFirst().getString().contains("Not enough space"), "Crafting explains why refill stopped");
        helper.succeed();
    }

    private static void withPickup(Runnable test) {
        var config = MagicShulkerBoxes.config();
        boolean pickup = config.pickupStorageEnabled, notices = config.refillFailureMessages;
        try { config.pickupStorageEnabled = true; config.refillFailureMessages = false; test.run(); }
        finally { config.pickupStorageEnabled = pickup; config.refillFailureMessages = notices; }
    }

    private static RecordingPlayer full(GameTestHelper helper) {
        var player = new RecordingPlayer(helper);
        for (int i = 0; i < 36; i++) player.getInventory().setItem(i, new ItemStack(Items.STONE, 64));
        return player;
    }
    private static ItemStack box(ItemStack... items) {
        var box = new ItemStack(Items.SHULKER_BOX);
        box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(items))); return box;
    }
    private static int stored(ItemStack box) { return box.get(DataComponents.CONTAINER).stream().mapToInt(ItemStack::getCount).sum(); }
    private static void check(GameTestHelper helper, boolean value, String message) { helper.assertTrue(value, Component.literal(message)); }

    private static final class RecordingPlayer extends ServerPlayer {
        final List<Component> notices = new ArrayList<>();
        boolean actionBar;
        @SuppressWarnings("removal")
        RecordingPlayer(GameTestHelper helper) {
            super(helper.getLevel().getServer(), helper.getLevel(), new GameProfile(UUID.randomUUID(), "MSBFeedback"), ClientInformation.createDefault());
            // Use the supported mock connection for server bookkeeping; capture visible notices at the player boundary.
            connection = helper.makeMockServerPlayerInLevel().connection;
            setGameMode(GameType.SURVIVAL);
        }
        @Override public void displayClientMessage(Component message, boolean actionBar) {
            notices.add(message); this.actionBar = actionBar;
        }
    }
}
