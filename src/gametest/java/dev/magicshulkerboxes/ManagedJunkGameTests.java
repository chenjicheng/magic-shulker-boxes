package dev.magicshulkerboxes;

import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public class ManagedJunkGameTests {
    @GameTest public void aThreeStackNativeReceiptCreatesDedicatedStorageBeforeInventoryFallback(GameTestHelper helper) {
        var player = player(helper);
        var config = MagicShulkerBoxes.config();
        boolean enabled = config.pickupStorageEnabled, personal = config.allowPlayerSettings, prefer = config.preferEmptyBoxesOverInventory;
        try {
            config.pickupStorageEnabled = true; config.allowPlayerSettings = false; config.preferEmptyBoxesOverInventory = false;
            player.getInventory().setItem(9, new ItemStack(Items.SHULKER_BOX));
            var chest = TestContainers.chest(helper, player);
            chest.setItem(0, new ItemStack(Items.STONE)); chest.getItem(0).setCount(192);
            var menu = ChestMenu.threeRows(1, player.getInventory(), chest); player.containerMenu = menu;
            menu.clicked(0, 0, ClickType.QUICK_MOVE, player);
            check(helper, chest.getItem(0).isEmpty(), "Vanilla completes source deduction before processing the whole receipt");
            check(helper, count(player.getInventory().getItem(9)) == 192 && !ManagedJunkBoxes.marked(player.getInventory().getItem(9)),
                    "The complete three-stack receipt becomes dedicated storage even with the small-item inventory preference");
            int loose = 0;
            for (int slot = 0; slot < 36; slot++) if (player.getInventory().getItem(slot).is(Items.STONE)) loose += player.getInventory().getItem(slot).getCount();
            check(helper, loose == 0, "Every actual received unit is stored exactly once");
        } finally {
            config.pickupStorageEnabled = enabled; config.allowPlayerSettings = personal; config.preferEmptyBoxesOverInventory = prefer;
        }
        helper.succeed();
    }

    @GameTest public void groupingOldRearrangementsCannotMultiplyTheRealReceiptBudget(GameTestHelper helper) {
        var player = player(helper);
        player.getInventory().setItem(9, new ItemStack(Items.STONE, 64));
        player.getInventory().setItem(18, new ItemStack(Items.SHULKER_BOX));
        var before = MenuStorage.snapshot(player.getInventory());
        player.getInventory().setItem(9, ItemStack.EMPTY);
        player.getInventory().setItem(10, new ItemStack(Items.STONE, 64));
        player.getInventory().setItem(11, new ItemStack(Items.STONE, 5));
        var config = new StorageConfig(); config.pickupStorageEnabled = true; config.preferEmptyBoxesOverInventory = true;
        MenuStorage.collectDeposited(player.getInventory(), before, config);
        int stored = 0, loose = 0;
        for (int slot = 0; slot < 36; slot++) {
            var stack = player.getInventory().getItem(slot);
            if (ShulkerStorage.isShulker(stack)) stored += stack.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).stream()
                    .filter(item -> item.is(Items.STONE)).mapToInt(ItemStack::getCount).sum();
            else if (stack.is(Items.STONE)) loose += stack.getCount();
        }
        check(helper, stored == 5 && loose == 64, "One shared net budget permits only the five genuinely added items");
        helper.succeed();
    }

    @GameTest public void nativeInventoryRemovalCannotTransferARoleToAnotherPlayer(GameTestHelper helper) {
        var first = player(helper);
        var second = player(helper);
        first.getInventory().setItem(9, managed());
        var originalReference = first.getInventory().getItem(9);
        var removed = first.getInventory().removeItemNoUpdate(9);
        check(helper, !ManagedJunkBoxes.marked(removed), "The public inventory removal boundary clears the role");
        second.getInventory().setItem(9, originalReference);
        check(helper, !ManagedJunkBoxes.marked(second.getInventory().getItem(9)), "A recipient does not inherit another player's role");
        check(helper, count(second.getInventory().getItem(9)) == 5, "Transfer preserves all actual contents");
        first.getInventory().setItem(10, managed());
        check(helper, !ManagedJunkBoxes.marked(first.getInventory().removeItem(10, 1)), "Partial inventory removal uses the same lifecycle");
        helper.succeed();
    }

    @GameTest public void nativeMoveAndDropCreateOneManagedBoxAndConserveEveryItem(GameTestHelper helper) {
        for (var mode : new StorageConfig.MakeSpaceMode[] {StorageConfig.MakeSpaceMode.MOVE_TO_BOX, StorageConfig.MakeSpaceMode.DROP_AND_PICKUP}) {
            var player = player(helper);
            var config = MagicShulkerBoxes.config();
            var previousMode = config.makeSpaceMode;
            boolean enabled = config.pickupStorageEnabled, personal = config.allowPlayerSettings;
            try {
                config.pickupStorageEnabled = true; config.allowPlayerSettings = false; config.makeSpaceMode = mode;
                for (int slot = 0; slot < 36; slot++) player.getInventory().setItem(slot, new ItemStack(Items.DIRT, 64));
                player.getInventory().setItem(9, new ItemStack(Items.SHULKER_BOX)); player.getInventory().getItem(9).setCount(16);
                var incoming = new ItemEntity(helper.getLevel(), player.getX(), player.getY(), player.getZ(), new ItemStack(Items.STONE, 5));
                incoming.setNoPickUpDelay(); helper.getLevel().addFreshEntity(incoming); incoming.playerTouch(player);
                check(helper, incoming.isRemoved(), "All five real items are received: " + mode);
                int boxes = 0, dirt = 0, stone = 0, managed = 0;
                for (int slot = 0; slot < 36; slot++) {
                    var stack = player.getInventory().getItem(slot);
                    if (ShulkerStorage.isShulker(stack)) {
                        boxes += stack.getCount();
                        if (ManagedJunkBoxes.marked(stack)) managed++;
                        for (var item : stack.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).stream().toList()) {
                            if (item.is(Items.DIRT)) dirt += item.getCount();
                            if (item.is(Items.STONE)) stone += item.getCount();
                        }
                    } else if (stack.is(Items.DIRT)) dirt += stack.getCount();
                }
                check(helper, boxes == 16 && dirt == 35 * 64 && stone == 5 && managed == 1,
                        "Both modes preserve boxes, actual quantities and one automatically created role: " + mode);
            } finally {
                config.pickupStorageEnabled = enabled; config.allowPlayerSettings = personal; config.makeSpaceMode = previousMode;
            }
        }
        helper.succeed();
    }

    @GameTest public void managedRoleNeverBypassesUnknownMenuOwnership(GameTestHelper helper) {
        var player = player(helper);
        var config = MagicShulkerBoxes.config();
        boolean enabled = config.pickupStorageEnabled, personal = config.allowPlayerSettings, prefer = config.preferEmptyBoxesOverInventory;
        try {
            config.pickupStorageEnabled = true; config.allowPlayerSettings = false; config.preferEmptyBoxesOverInventory = true;
            var box = managed(); player.getInventory().setItem(9, box);
            var source = new ItemBackedMenuGameTests.ItemBackedContainer(box);
            var menu = ChestMenu.threeRows(1, player.getInventory(), source); player.containerMenu = menu;
            menu.clicked(0, 0, ClickType.QUICK_MOVE, player);
            int loose = 0;
            for (int slot = 0; slot < 36; slot++) if (player.getInventory().getItem(slot).is(Items.STONE)) loose += player.getInventory().getItem(slot).getCount();
            check(helper, loose == 5 && count(player.getInventory().getItem(9)) == 0, "Unknown writers keep exclusive ownership after vanilla transfer");
        } finally {
            config.pickupStorageEnabled = enabled; config.allowPlayerSettings = personal; config.preferEmptyBoxesOverInventory = prefer;
        }
        helper.succeed();
    }

    @GameTest public void cursorExitClearsTheRoleAndReturningDoesNotRestoreIt(GameTestHelper helper) {
        var player = player(helper);
        player.getInventory().setItem(9, managed());
        var menu = ChestMenu.threeRows(1, player.getInventory(), TestContainers.chest(helper, player));
        player.containerMenu = menu;
        menu.clicked(inventorySlot(menu, player, 9), 0, ClickType.PICKUP, player);
        check(helper, !ManagedJunkBoxes.marked(menu.getCarried()), "A box on the cursor has left the backpack");
        menu.clicked(inventorySlot(menu, player, 18), 0, ClickType.PICKUP, player);
        check(helper, !ManagedJunkBoxes.marked(player.getInventory().getItem(18)), "Returning a box cannot recreate its role");
        check(helper, count(player.getInventory().getItem(18)) == 5, "Exit changes metadata only");
        helper.succeed();
    }

    @GameTest public void ownInventoryCursorMovesKeepTemporaryBackpackOwnership(GameTestHelper helper) {
        var player = player(helper);
        player.getInventory().setItem(9, managed());
        var menu = player.inventoryMenu;
        menu.clicked(inventorySlot(menu, player, 9), 0, ClickType.PICKUP, player);
        check(helper, ManagedJunkBoxes.marked(menu.getCarried()), "Own-inventory arrangement has not left backpack ownership");
        menu.clicked(inventorySlot(menu, player, 18), 0, ClickType.PICKUP, player);
        check(helper, ManagedJunkBoxes.marked(player.getInventory().getItem(18)), "Putting it back preserves the same role");
        helper.succeed();
    }

    @GameTest public void shiftToAChestClearsRoleAndPreservesOtherData(GameTestHelper helper) {
        var player = player(helper);
        var original = managed();
        var data = original.get(DataComponents.CUSTOM_DATA).copyTag();
        data.putInt("other_mod_data", 17);
        original.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
        original.set(DataComponents.CUSTOM_NAME, Component.literal("Retained name"));
        player.getInventory().setItem(9, original);
        var chest = TestContainers.chest(helper, player);
        var menu = ChestMenu.threeRows(1, player.getInventory(), chest);
        player.containerMenu = menu;
        menu.clicked(inventorySlot(menu, player, 9), 0, ClickType.QUICK_MOVE, player);
        var stored = chest.getItem(0);
        check(helper, !ManagedJunkBoxes.marked(stored), "External containers cannot retain a junk role");
        check(helper, count(stored) == 5 && stored.get(DataComponents.CUSTOM_DATA).copyTag().getIntOr("other_mod_data", 0) == 17,
                "Contents and other custom data remain intact");
        check(helper, stored.getHoverName().getString().equals("Retained name"), "Names survive cleanup");
        menu.clicked(0, 0, ClickType.QUICK_MOVE, player);
        check(helper, player.getInventory().getItem(9).isEmpty() || !ManagedJunkBoxes.marked(player.getInventory().getItem(9)),
                "Shift back does not restore the role");
        helper.succeed();
    }

    @GameTest public void aBackpackHotbarSwapPreservesTheRole(GameTestHelper helper) {
        var player = player(helper);
        player.getInventory().setItem(9, managed());
        player.inventoryMenu.clicked(inventorySlot(player.inventoryMenu, player, 9), 0, ClickType.SWAP, player);
        check(helper, ManagedJunkBoxes.marked(player.getInventory().getItem(0)), "Hotbar moves remain inside the backpack");
        helper.succeed();
    }

    @GameTest public void movingToOffhandClearsTheRole(GameTestHelper helper) {
        var player = player(helper);
        player.getInventory().setItem(9, managed());
        player.inventoryMenu.clicked(inventorySlot(player.inventoryMenu, player, 9), 40, ClickType.SWAP, player);
        check(helper, !ManagedJunkBoxes.marked(player.getOffhandItem()), "Offhand is outside the 36 backpack positions");
        check(helper, count(player.getOffhandItem()) == 5, "Offhand transfer preserves contents");
        helper.succeed();
    }

    @GameTest public void droppedBoxesHaveNoRoleAndCanBePickedUpWithoutRestoringIt(GameTestHelper helper) {
        var player = player(helper);
        player.getInventory().setItem(9, managed());
        var entity = new ItemEntity(helper.getLevel(), player.getX(), player.getY(), player.getZ(), player.getInventory().removeItemNoUpdate(9));
        entity.setNoPickUpDelay(); helper.getLevel().addFreshEntity(entity);
        check(helper, !ManagedJunkBoxes.marked(entity.getItem()), "Ground items cannot become wandering junk boxes");
        entity.playerTouch(player);
        for (int slot = 0; slot < 36; slot++) check(helper, !ManagedJunkBoxes.marked(player.getInventory().getItem(slot)), "Pickup does not restore a role");
        helper.succeed();
    }

    @GameTest public void placedBoxesDoNotCarryTheRoleIntoBlockEntityData(GameTestHelper helper) {
        var player = player(helper);
        var ground = helper.absolutePos(new BlockPos(1, 0, 1));
        helper.getLevel().setBlock(ground, Blocks.STONE.defaultBlockState(), 3);
        player.getInventory().setItem(0, managed());
        var hit = new BlockHitResult(Vec3.atCenterOf(ground).add(0, 0.5, 0), Direction.UP, ground, false);
        var result = player.getMainHandItem().useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit));
        check(helper, result.consumesAction(), "The native placement succeeds");
        var block = helper.getLevel().getBlockEntity(ground.above());
        check(helper, block != null, "A real shulker block exists");
        var data = block.collectComponents().get(DataComponents.CUSTOM_DATA);
        check(helper, data == null || !data.copyTag().getBooleanOr(ManagedJunkBoxes.MARKER, false), "Placed boxes have no managed role");
        helper.succeed();
    }

    @SuppressWarnings("removal") static ServerPlayer player(GameTestHelper helper) {
        var player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(),
                new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "MSBManaged"),
                net.minecraft.server.level.ClientInformation.createDefault());
        player.connection = helper.makeMockServerPlayerInLevel().connection;
        player.setGameMode(GameType.SURVIVAL); player.getInventory().clearContent();
        player.setPos(Vec3.atCenterOf(helper.absolutePos(new BlockPos(1, 1, 1))));
        return player;
    }

    static ItemStack managed() {
        var box = new ItemStack(Items.SHULKER_BOX);
        box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.STONE, 5))));
        var tag = new CompoundTag(); tag.putBoolean(ManagedJunkBoxes.MARKER, true);
        box.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return box;
    }

    static int inventorySlot(AbstractContainerMenu menu, ServerPlayer player, int index) {
        for (int slot = 0; slot < menu.slots.size(); slot++)
            if (menu.slots.get(slot).container == player.getInventory() && menu.slots.get(slot).getContainerSlot() == index) return slot;
        throw new AssertionError("Inventory position missing from native menu");
    }

    static int count(ItemStack box) { return box.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).stream().mapToInt(ItemStack::getCount).sum(); }
    static void check(GameTestHelper helper, boolean condition, String message) { helper.assertTrue(condition, Component.literal(message)); }
}
