package dev.magicshulkerboxes;

import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.GameType;

public class JunkSlotsGameTests {
    @GameTest public void nativePickupMakesOneFilledJunkBoxWithoutDuplicatingTheEmptyStack(GameTestHelper helper) throws Exception {
        for (var mode : new StorageConfig.MakeSpaceMode[] {StorageConfig.MakeSpaceMode.MOVE_TO_BOX, StorageConfig.MakeSpaceMode.DROP_AND_PICKUP}) {
            var player = player(helper);
            var config = MagicShulkerBoxes.config();
            var previousMode = config.makeSpaceMode;
            boolean enabled = config.pickupStorageEnabled, personal = config.allowPlayerSettings;
            var store = MagicShulkerBoxes.junkSlots(helper.getLevel().getServer());
            try {
                config.pickupStorageEnabled = true; config.allowPlayerSettings = false; config.makeSpaceMode = mode;
                for (int slot = 0; slot < 36; slot++) player.getInventory().setItem(slot, new ItemStack(Items.DIRT, 64));
                player.getInventory().setItem(9, box()); player.getInventory().getItem(9).setCount(16);
                var choice = store.read(player.getUUID()); store.update(player.getUUID(), choice.revision(), 1L << 9);
                var incoming = new ItemEntity(helper.getLevel(), player.getX(), player.getY(), player.getZ(), new ItemStack(Items.STONE, 5));
                incoming.setNoPickUpDelay(); helper.getLevel().addFreshEntity(incoming); incoming.playerTouch(player);
                check(helper, incoming.isRemoved(), "All five real items are received after native pickup");
                check(helper, player.getInventory().getItem(9).getCount() == 1 && contents(player.getInventory().getItem(9)) == 133,
                        "Both relocated dirt stacks and incoming stones stay in the designated box: " + mode);
                int boxes = 0, dirt = 0, stone = 0;
                for (int slot = 0; slot < 36; slot++) {
                    var stack = player.getInventory().getItem(slot);
                    if (ShulkerStorage.isShulker(stack)) {
                        boxes += stack.getCount();
                        for (var item : stack.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).stream().toList()) {
                            if (item.is(Items.DIRT)) dirt += item.getCount();
                            if (item.is(Items.STONE)) stone += item.getCount();
                        }
                    } else if (stack.is(Items.DIRT)) dirt += stack.getCount();
                }
                check(helper, boxes == 16 && dirt == 35 * 64 && stone == 5, "Every box and item is conserved: " + mode);
            } finally {
                var current = store.read(player.getUUID()); store.update(player.getUUID(), current.revision(), 0);
                config.pickupStorageEnabled = enabled; config.allowPlayerSettings = personal; config.makeSpaceMode = previousMode;
            }
        }
        helper.succeed();
    }

    @GameTest public void selectedSourceBoxStillCannotBeWrittenByAutomaticMenuCollection(GameTestHelper helper) throws Exception {
        var player = player(helper); var config = MagicShulkerBoxes.config();
        boolean enabled = config.pickupStorageEnabled, personal = config.allowPlayerSettings, prefer = config.preferEmptyBoxesOverInventory;
        var store = MagicShulkerBoxes.junkSlots(helper.getLevel().getServer());
        try {
            config.pickupStorageEnabled = true; config.allowPlayerSettings = false; config.preferEmptyBoxesOverInventory = true;
            player.getInventory().setItem(9, box(new ItemStack(Items.STONE, 5)));
            var initial = store.read(player.getUUID()); store.update(player.getUUID(), initial.revision(), 1L << 9);
            var source = new ItemBackedMenuGameTests.ItemBackedContainer(player.getInventory().getItem(9));
            var menu = ChestMenu.threeRows(1, player.getInventory(), source); player.containerMenu = menu;
            menu.clicked(0, 0, ClickType.QUICK_MOVE, player);
            int loose = 0;
            for (int slot = 0; slot < 36; slot++) if (player.getInventory().getItem(slot).is(Items.STONE)) loose += player.getInventory().getItem(slot).getCount();
            check(helper, loose == 5 && contents(player.getInventory().getItem(9)) == 0,
                    "Explicit junk roles cannot bypass the 0.9.2 source ownership protection");
        } finally {
            var current = store.read(player.getUUID()); store.update(player.getUUID(), current.revision(), 0);
            config.pickupStorageEnabled = enabled; config.allowPlayerSettings = personal; config.preferEmptyBoxesOverInventory = prefer;
        }
        helper.succeed();
    }

    @GameTest public void networkChoicesAreValidatedAndRemainOwnedByTheirAuthenticatedPlayer(GameTestHelper helper) throws Exception {
        var first = player(helper); var second = player(helper);
        var store = MagicShulkerBoxes.junkSlots(helper.getLevel().getServer());
        var start = store.read(first.getUUID());
        try {
            var saved = JunkSlotsNetwork.accept(first, new JunkSlotsNetwork.Save(1, start.revision(), (1L << 9) | (1L << 35)));
            check(helper, saved.status() == JunkSlotsNetwork.SAVED && saved.mask() == ((1L << 9) | (1L << 35)), "Server confirms only valid slot choices");
            check(helper, MagicShulkerBoxes.configFor(first).junkBoxSlots == saved.mask()
                            && MagicShulkerBoxes.configFor(second).junkBoxSlots == 0 && MagicShulkerBoxes.config().junkBoxSlots == 0,
                    "Player roles do not mutate shared defaults or another account");
            var invalid = JunkSlotsNetwork.accept(first, new JunkSlotsNetwork.Save(2, saved.revision(), 1L << 36));
            check(helper, invalid.status() == JunkSlotsNetwork.INVALID && invalid.mask() == saved.mask(), "Equipment-slot injection is rejected");
            var stale = JunkSlotsNetwork.accept(first, new JunkSlotsNetwork.Save(3, start.revision(), 0));
            check(helper, stale.status() == JunkSlotsNetwork.STALE && stale.mask() == saved.mask(), "Stale saves cannot erase current choices");
        } finally {
            var current = store.read(first.getUUID()); store.update(first.getUUID(), current.revision(), 0);
        }
        helper.succeed();
    }

    @SuppressWarnings("removal") private static ServerPlayer player(GameTestHelper helper) {
        var player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(),
                new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "MSBJunk"),
                net.minecraft.server.level.ClientInformation.createDefault());
        player.connection = helper.makeMockServerPlayerInLevel().connection;
        player.setGameMode(GameType.SURVIVAL); player.getInventory().clearContent(); return player;
    }
    private static ItemStack box(ItemStack... items) {
        var box = new ItemStack(Items.SHULKER_BOX); box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(items))); return box;
    }
    private static int contents(ItemStack box) { return box.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).stream().mapToInt(ItemStack::getCount).sum(); }
    private static void check(GameTestHelper helper, boolean success, String text) { helper.assertTrue(success, Component.literal(text)); }
}
