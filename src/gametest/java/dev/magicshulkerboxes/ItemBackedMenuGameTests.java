package dev.magicshulkerboxes;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.GameType;

/** A menu editing a carried item, without any dependency on a particular opening mod. */
public class ItemBackedMenuGameTests {
    @GameTest
    public void originalInventoryRearrangementIsNotANewReceipt(GameTestHelper helper) {
        withStorage(() -> {
            var player = player(helper);
            player.getInventory().getItem(9).set(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
            player.getInventory().setItem(10, new ItemStack(Items.DIRT, 12));
            var dirtBox = new ItemStack(Items.SHULKER_BOX);
            dirtBox.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(java.util.List.of(new ItemStack(Items.DIRT))));
            player.getInventory().setItem(35, dirtBox);
            var chest = TestContainers.chest(helper, player);
            chest.setItem(0, new ItemStack(Items.STONE, 5));
            var menu = new ChestMenu(net.minecraft.world.inventory.MenuType.GENERIC_9x3, 1, player.getInventory(), chest, 3) {
                @Override
                public ItemStack quickMoveStack(net.minecraft.world.entity.player.Player owner, int slot) {
                    var moved = super.quickMoveStack(owner, slot);
                    if (getSlot(0).getItem().isEmpty() && player.getInventory().getItem(10).is(Items.DIRT)) {
                        player.getInventory().setItem(11, player.getInventory().getItem(10));
                        player.getInventory().setItem(10, ItemStack.EMPTY);
                    }
                    return moved;
                }
            };
            player.containerMenu = menu;
            menu.clicked(0, 0, ClickType.QUICK_MOVE, player);
            helper.assertTrue(player.getInventory().getItem(11).is(Items.DIRT)
                            && player.getInventory().getItem(11).getCount() == 12
                            && player.getInventory().getItem(35).get(DataComponents.CONTAINER).stream()
                                    .mapToInt(ItemStack::getCount).sum() == 1,
                    Component.literal("Native relocation of existing dirt is not treated as newly received stock"));
            helper.assertTrue(stored(player) == 5, Component.literal("Only the five newly received stones are stored"));
        });
        helper.succeed();
    }

    @GameTest
    public void pickupCallbackFinishesBeforePostTransferStorage(GameTestHelper helper) {
        withStorage(() -> {
            var player = new PickupCallbackPlayer(helper);
            player.getInventory().clearContent();
            var box = new ItemStack(Items.SHULKER_BOX);
            box.set(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
            player.getInventory().setItem(9, box);
            var dropped = new net.minecraft.world.entity.item.ItemEntity(helper.getLevel(),
                    player.getX(), player.getY(), player.getZ(), new ItemStack(Items.STONE, 5));
            dropped.setNoPickUpDelay();
            dropped.playerTouch(player);
            helper.assertTrue(player.pickups == 1 && loose(player) == 0 && stored(player) == 5 && dropped.isRemoved(),
                    Component.literal("One completed native pickup supplies exactly five items for post-storage"));
        });
        helper.succeed();
    }

    @GameTest
    public void sourceCallbackFinishesBeforePostTransferStorage(GameTestHelper helper) {
        withStorage(() -> {
            var player = player(helper);
            player.getInventory().getItem(9).set(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
            var pos = helper.absolutePos(new net.minecraft.core.BlockPos(1, 0, 1));
            helper.getLevel().setBlock(pos, net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState(), 3);
            var chest = (net.minecraft.world.level.block.entity.ChestBlockEntity) helper.getLevel().getBlockEntity(pos);
            player.setPos(pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5);
            chest.setItem(0, new ItemStack(Items.STONE, 5));
            var menu = ChestMenu.threeRows(1, player.getInventory(), chest);
            boolean[] callback = {false};
            menu.slots.set(0, new net.minecraft.world.inventory.Slot(chest, 0, 0, 0) {
                @Override
                public void setChanged() {
                    if (getItem().isEmpty()) {
                        callback[0] = true;
                        helper.assertTrue(loose(player) == 5 && stored(player) == 0,
                                Component.literal("Source callbacks observe completed vanilla inventory transfer before MSB storage"));
                    }
                    super.setChanged();
                }
            });
            player.containerMenu = menu;
            menu.clicked(0, 0, ClickType.QUICK_MOVE, player);
            helper.assertTrue(callback[0] && loose(player) == 0 && stored(player) == 5,
                    Component.literal("Only after the original callback may the received items enter a box"));
        });
        helper.succeed();
    }

    @GameTest
    public void shiftTakeDoesNotDuplicateTheEditedBox(GameTestHelper helper) {
        withStorage(() -> {
            var player = player(helper);
            var source = new ItemBackedContainer(player.getInventory().getItem(9));
            var menu = ChestMenu.threeRows(1, player.getInventory(), source);
            player.containerMenu = menu;
            trace("before Shift take", player, source, menu);
            menu.clicked(0, 0, ClickType.QUICK_MOVE, player);
            trace("after Shift take", player, source, menu);
            source.setChanged();
            trace("after later menu save", player, source, menu);
            var ops = player.registryAccess().createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE);
            var tag = ItemStack.CODEC.encodeStart(ops, player.getInventory().getItem(9)).getOrThrow();
            var savedBox = ItemStack.CODEC.parse(ops, tag).getOrThrow();
            int persisted = savedBox.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY)
                    .stream().filter(stack -> stack.is(Items.STONE)).mapToInt(ItemStack::getCount).sum();
            var reopened = new ItemBackedContainer(player.getInventory().getItem(9));
            MagicShulkerBoxes.LOGGER.info("MSB duplication diagnosis: serialized box={}, reopened box={}, total={}",
                    persisted, count(reopened), loose(player) + stored(player));
            helper.assertTrue(loose(player) + stored(player) == 5,
                    Component.literal("Expected five stones after transfer, save and reopen; actual loose="
                            + loose(player) + ", box=" + stored(player) + ", serialized=" + persisted));
            helper.assertTrue(loose(player) == 5 && stored(player) == 0,
                    Component.literal("Taking five stones leaves exactly five outside the edited box"));
        });
        helper.succeed();
    }

    @GameTest
    public void vanillaItemBackedShiftTakeConservesTheSameFiveStones(GameTestHelper helper) {
        withStorage(() -> {
            MagicShulkerBoxes.config().pickupStorageEnabled = false;
            var player = player(helper);
            var source = new ItemBackedContainer(player.getInventory().getItem(9));
            var menu = ChestMenu.threeRows(1, player.getInventory(), source);
            player.containerMenu = menu;
            menu.clicked(0, 0, ClickType.QUICK_MOVE, player);
            source.setChanged();
            trace("vanilla control after Shift and save", player, source, menu);
            helper.assertTrue(loose(player) == 5 && stored(player) == 0,
                    Component.literal("The same item-backed menu conserves items without MSB storage"));
        });
        helper.succeed();
    }

    @GameTest
    public void cursorTakeDoesNotReturnToTheEditedBox(GameTestHelper helper) {
        withStorage(() -> {
            var player = player(helper);
            var source = new ItemBackedContainer(player.getInventory().getItem(9));
            var menu = ChestMenu.threeRows(1, player.getInventory(), source);
            player.containerMenu = menu;
            menu.clicked(0, 0, ClickType.PICKUP, player);
            trace("after cursor extraction", player, source, menu);
            menu.clicked(28, 0, ClickType.PICKUP, player);
            trace("after cursor deposit", player, source, menu);
            helper.assertTrue(loose(player) == 5 && stored(player) == 0 && menu.getCarried().isEmpty(),
                    Component.literal("Manual cursor deposit stays in the player's inventory"));
        });
        helper.succeed();
    }

    @GameTest
    public void hotbarTakeDoesNotReturnToTheEditedBox(GameTestHelper helper) {
        withStorage(() -> {
            var player = player(helper);
            var source = new ItemBackedContainer(player.getInventory().getItem(9));
            var menu = ChestMenu.threeRows(1, player.getInventory(), source);
            player.containerMenu = menu;
            menu.clicked(0, 0, ClickType.SWAP, player);
            trace("after hotbar extraction", player, source, menu);
            helper.assertTrue(player.getInventory().getItem(0).getCount() == 5 && stored(player) == 0,
                    Component.literal("Hotbar extraction is not automatically put back into the box"));
        });
        helper.succeed();
    }

    @GameTest
    public void pickupDoesNotRewriteBoxesWhileAnUnownedMenuIsEditingThem(GameTestHelper helper) {
        withStorage(() -> {
            var player = player(helper);
            var source = new ItemBackedContainer(player.getInventory().getItem(9));
            player.containerMenu = ChestMenu.threeRows(1, player.getInventory(), source);
            var entity = new net.minecraft.world.entity.item.ItemEntity(helper.getLevel(),
                    player.getX(), player.getY(), player.getZ(), new ItemStack(Items.STONE, 2));
            entity.setNoPickUpDelay();
            helper.getLevel().addFreshEntity(entity);
            entity.playerTouch(player);
            trace("after ground pickup while editing", player, source, player.containerMenu);
            helper.assertTrue(loose(player) == 2 && stored(player) == 5,
                    Component.literal("Ground pickup must not race an active item-container writer"));
        });
        helper.succeed();
    }

    private static int count(net.minecraft.world.Container container) {
        int total = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            var stack = container.getItem(slot);
            if (stack.is(Items.STONE)) total += stack.getCount();
        }
        return total;
    }

    private static void trace(String checkpoint, ServerPlayer player, ItemBackedContainer source,
                              net.minecraft.world.inventory.AbstractContainerMenu menu) {
        MagicShulkerBoxes.LOGGER.info(
                "MSB duplication diagnosis {}: loose={}, box={}, menu={}, cursor={}, menuBackingIsCurrent={}",
                checkpoint, loose(player), stored(player), count(source), menu.getCarried().getCount(),
                source.backing == player.getInventory().getItem(9));
    }

    private static int loose(ServerPlayer player) {
        int count = 0;
        for (int slot = 0; slot < 36; slot++) {
            var stack = player.getInventory().getItem(slot);
            if (stack.is(Items.STONE)) count += stack.getCount();
        }
        return count;
    }

    private static int stored(ServerPlayer player) {
        return player.getInventory().getItem(9).getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY)
                .stream().filter(stack -> stack.is(Items.STONE)).mapToInt(ItemStack::getCount).sum();
    }

    @SuppressWarnings("removal")
    private static ServerPlayer player(GameTestHelper helper) {
        var player = helper.makeMockServerPlayerInLevel();
        player.setGameMode(GameType.SURVIVAL);
        player.getInventory().clearContent();
        var box = new ItemStack(Items.SHULKER_BOX);
        box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(java.util.List.of(new ItemStack(Items.STONE, 5))));
        player.getInventory().setItem(9, box);
        return player;
    }

    private static void withStorage(Runnable test) {
        var config = MagicShulkerBoxes.config();
        boolean enabled = config.pickupStorageEnabled;
        boolean preferBoxes = config.preferEmptyBoxesOverInventory;
        boolean personal = config.allowPlayerSettings;
        try {
            config.pickupStorageEnabled = true;
            config.preferEmptyBoxesOverInventory = true;
            config.allowPlayerSettings = false;
            test.run();
        } finally {
            config.pickupStorageEnabled = enabled;
            config.preferEmptyBoxesOverInventory = preferBoxes;
            config.allowPlayerSettings = personal;
        }
    }

    /** Some opening mods retain the original ItemStack while our storage replaces its inventory slot. */
    static final class ItemBackedContainer extends SimpleContainer {
        private final ItemStack backing;

        ItemBackedContainer(ItemStack backing) {
            super(27);
            this.backing = backing;
            var contents = NonNullList.withSize(27, ItemStack.EMPTY);
            backing.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).copyInto(contents);
            for (int slot = 0; slot < 27; slot++) super.setItem(slot, contents.get(slot).copy());
        }

        @Override
        public void setChanged() {
            if (backing == null) return;
            var contents = NonNullList.withSize(27, ItemStack.EMPTY);
            for (int slot = 0; slot < 27; slot++) contents.set(slot, getItem(slot).copy());
            backing.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(contents));
        }
    }

    private static final class PickupCallbackPlayer extends ServerPlayer {
        private final GameTestHelper helper;
        int pickups;

        @SuppressWarnings("removal")
        PickupCallbackPlayer(GameTestHelper helper) {
            super(helper.getLevel().getServer(), helper.getLevel(),
                    new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "MSBPickupOrder"),
                    net.minecraft.server.level.ClientInformation.createDefault());
            this.helper = helper;
            connection = helper.makeMockServerPlayerInLevel().connection;
            setGameMode(GameType.SURVIVAL);
        }

        @Override
        public void onItemPickup(net.minecraft.world.entity.item.ItemEntity entity) {
            pickups++;
            helper.assertTrue(loose(this) == 5 && stored(this) == 0,
                    Component.literal("Native pickup callbacks finish while newly received items are still loose"));
            super.onItemPickup(entity);
        }
    }
}
