package dev.magicshulkerboxes;

import java.util.List;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.GameType;

public class MenuStorageGameTests {
    @GameTest
    public void selectingATradeDoesNotCollectItsAutomaticPaymentRefund(GameTestHelper h) {
        withConfig(
                false,
                () -> {
                    var p = player(h);
                    p.getInventory()
                            .getItem(9)
                            .set(
                                    DataComponents.CONTAINER,
                                    ItemContainerContents.fromItems(
                                            List.of(
                                                    new ItemStack(Items.STONE),
                                                    new ItemStack(Items.DIRT))));
                    var villager = new Villager(EntityType.VILLAGER, h.getLevel());
                    villager.setTradingPlayer(p);
                    var offer =
                            new MerchantOffer(
                                    new ItemCost(Items.EMERALD, 1),
                                    new ItemStack(Items.STONE, 4),
                                    10,
                                    3,
                                    0);
                    var offers = new MerchantOffers();
                    offers.add(offer);
                    villager.setOffers(offers);
                    var menu = new MerchantMenu(2, p.getInventory(), villager);
                    p.containerMenu = menu;
                    menu.getSlot(0).set(new ItemStack(Items.EMERALD, 3));
                    menu.slotsChanged(menu.getSlot(0).container);
                    menu.tryMoveItems(0);
                    check(
                            h,
                            menu.getSlot(0).getItem().getCount() == 3
                                    && menu.getSlot(2).getItem().getCount() == 4,
                            "Trade selection keeps the automatic payment refund available for"
                                + " vanilla autofill");
                    check(h, offer.getUses() == 0, "Selecting a trade does not buy anything");
                });
        h.succeed();
    }

    @GameTest
    public void shiftTransferStoresBeforeInventoryAndNormalClickWaitsForDeposit(GameTestHelper h) {
        withConfig(
                false,
                () -> {
                    var p = player(h);
                    var chest = new SimpleContainer(27);
                    chest.setItem(0, new ItemStack(Items.STONE, 12));
                    var menu = new ChestMenu(MenuType.GENERIC_9x3, 1, p.getInventory(), chest, 3);
                    p.containerMenu = menu;
                    menu.clicked(0, 0, ClickType.QUICK_MOVE, p);
                    check(
                            h,
                            stored(p) == 12 && chest.getItem(0).isEmpty(),
                            "Shift transfers into the box");
                    chest.setItem(1, new ItemStack(Items.STONE, 3));
                    menu.clicked(1, 0, ClickType.PICKUP, p);
                    check(
                            h,
                            menu.getCarried().getCount() == 3 && stored(p) == 12,
                            "Cursor contents are not collected");
                    menu.clicked(28, 0, ClickType.PICKUP, p);
                    check(
                            h,
                            menu.getCarried().isEmpty()
                                    && stored(p) == 15
                                    && p.getInventory().getItem(10).isEmpty(),
                            "Ordinary deposit is collected");
                });
        h.succeed();
    }

    @GameTest
    public void fullInventoryOverflowAndPartialBoxCapacityConserveAllItems(GameTestHelper h) {
        withConfig(
                true,
                () -> {
                    var p = player(h);
                    for (int i = 0; i < 36; i++)
                        if (i != 9) p.getInventory().setItem(i, new ItemStack(Items.DIRT, 64));
                    var contents = net.minecraft.core.NonNullList.withSize(27, ItemStack.EMPTY);
                    for (int i = 0; i < 27; i++)
                        contents.set(i, new ItemStack(Items.STONE, i == 0 ? 63 : 64));
                    p.getInventory()
                            .getItem(9)
                            .set(
                                    DataComponents.CONTAINER,
                                    ItemContainerContents.fromItems(contents));
                    var chest = new SimpleContainer(27);
                    chest.setItem(0, new ItemStack(Items.STONE, 5));
                    var menu = new ChestMenu(MenuType.GENERIC_9x3, 1, p.getInventory(), chest, 3);
                    p.containerMenu = menu;
                    menu.clicked(0, 0, ClickType.QUICK_MOVE, p);
                    check(
                            h,
                            stored(p) == 27 * 64 && chest.getItem(0).getCount() == 4,
                            "Only available box capacity is consumed");
                });
        h.succeed();
    }

    @GameTest
    public void overflowLeavesRoomyInventoryAndDisabledStorageAlone(GameTestHelper h) {
        withConfig(
                true,
                () -> {
                    var p = player(h);
                    var chest = new SimpleContainer(27);
                    chest.setItem(0, new ItemStack(Items.STONE, 5));
                    var menu = new ChestMenu(MenuType.GENERIC_9x3, 1, p.getInventory(), chest, 3);
                    p.containerMenu = menu;
                    menu.clicked(0, 0, ClickType.QUICK_MOVE, p);
                    check(
                            h,
                            stored(p) == 0 && chest.getItem(0).isEmpty(),
                            "Overflow retains vanilla inventory priority");
                    MagicShulkerBoxes.config().pickupStorageEnabled = false;
                    chest.setItem(1, new ItemStack(Items.STONE, 2));
                    menu.clicked(1, 0, ClickType.QUICK_MOVE, p);
                    check(
                            h,
                            stored(p) == 0 && chest.getItem(1).isEmpty(),
                            "Disabled storage retains vanilla transfer");
                });
        h.succeed();
    }

    @GameTest
    public void deniedSlotCannotBeCollectedAndTradeCannotPartiallyCharge(GameTestHelper h) {
        withConfig(
                false,
                () -> {
                    var p = player(h);
                    var chest = new SimpleContainer(27);
                    chest.setItem(0, new ItemStack(Items.STONE, 5));
                    var menu = new ChestMenu(MenuType.GENERIC_9x3, 1, p.getInventory(), chest, 3);
                    menu.slots.set(
                            0,
                            new net.minecraft.world.inventory.Slot(chest, 0, 0, 0) {
                                @Override
                                public boolean mayPickup(
                                        net.minecraft.world.entity.player.Player owner) {
                                    return false;
                                }
                            });
                    p.containerMenu = menu;
                    menu.clicked(0, 0, ClickType.QUICK_MOVE, p);
                    check(
                            h,
                            chest.getItem(0).getCount() == 5 && stored(p) == 0,
                            "Denied slot is not collected");
                    for (int i = 0; i < 36; i++)
                        if (i != 9) p.getInventory().setItem(i, new ItemStack(Items.DIRT, 64));
                    var contents = net.minecraft.core.NonNullList.withSize(27, ItemStack.EMPTY);
                    for (int i = 0; i < 27; i++)
                        contents.set(i, new ItemStack(Items.STONE, i == 0 ? 63 : 64));
                    p.getInventory()
                            .getItem(9)
                            .set(
                                    DataComponents.CONTAINER,
                                    ItemContainerContents.fromItems(contents));
                    var villager = new Villager(EntityType.VILLAGER, h.getLevel());
                    villager.setTradingPlayer(p);
                    var offer =
                            new MerchantOffer(
                                    new ItemCost(Items.EMERALD, 1),
                                    new ItemStack(Items.STONE, 4),
                                    10,
                                    3,
                                    0);
                    var offers = new MerchantOffers();
                    offers.add(offer);
                    villager.setOffers(offers);
                    var trade = new MerchantMenu(2, p.getInventory(), villager);
                    p.containerMenu = trade;
                    trade.getSlot(0).set(new ItemStack(Items.EMERALD));
                    trade.slotsChanged(trade.getSlot(0).container);
                    trade.clicked(2, 0, ClickType.QUICK_MOVE, p);
                    check(
                            h,
                            offer.getUses() == 0
                                    && villager.getVillagerXp() == 0
                                    && trade.getSlot(0).getItem().getCount() == 1,
                            "Insufficient result capacity leaves costs, uses and XP unchanged");
                    check(
                            h,
                            stored(p) == 27 * 64 - 1 && trade.getSlot(2).getItem().getCount() == 4,
                            "No partial trade result is extracted");
                });
        h.succeed();
    }

    @GameTest
    public void shiftTradesKeepCostsUsesXpAndOrdinaryResultOnCursor(GameTestHelper h) {
        withConfig(
                false,
                () -> {
                    var p = player(h);
                    var villager = new Villager(EntityType.VILLAGER, h.getLevel());
                    villager.setTradingPlayer(p);
                    var offer =
                            new MerchantOffer(
                                    new ItemCost(Items.EMERALD, 1),
                                    new ItemStack(Items.STONE, 4),
                                    10,
                                    3,
                                    0);
                    var offers = new MerchantOffers();
                    offers.add(offer);
                    villager.setOffers(offers);
                    var menu = new MerchantMenu(2, p.getInventory(), villager);
                    p.containerMenu = menu;
                    menu.getSlot(0).set(new ItemStack(Items.EMERALD, 3));
                    menu.slotsChanged(menu.getSlot(0).container);
                    menu.clicked(2, 0, ClickType.QUICK_MOVE, p);
                    check(
                            h,
                            stored(p) == 12
                                    && offer.getUses() == 3
                                    && villager.getVillagerXp() == 9,
                            "Every Shift trade charges and grants XP exactly once");
                    check(
                            h,
                            menu.getSlot(0).getItem().isEmpty(),
                            "All three payments were consumed");
                    menu.getSlot(0).set(new ItemStack(Items.EMERALD));
                    menu.slotsChanged(menu.getSlot(0).container);
                    menu.clicked(2, 0, ClickType.PICKUP, p);
                    check(
                            h,
                            menu.getCarried().getCount() == 4
                                    && stored(p) == 12
                                    && offer.getUses() == 4,
                            "Ordinary trade stays on cursor");
                    menu.clicked(4, 0, ClickType.PICKUP, p);
                    check(
                            h,
                            stored(p) == 16 && menu.getCarried().isEmpty(),
                            "Purchased cursor item is stored on deposit");
                });
        h.succeed();
    }

    @SuppressWarnings("removal")
    private static ServerPlayer player(GameTestHelper h) {
        var p = h.makeMockServerPlayerInLevel();
        p.setGameMode(GameType.SURVIVAL);
        var box = new ItemStack(Items.SHULKER_BOX);
        box.set(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
        p.getInventory().setItem(9, box);
        return p;
    }

    private static int stored(ServerPlayer p) {
        return p
                .getInventory()
                .getItem(9)
                .getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY)
                .stream()
                .filter(s -> s.is(Items.STONE))
                .mapToInt(ItemStack::getCount)
                .sum();
    }

    private static void withConfig(boolean overflow, Runnable test) {
        var c = MagicShulkerBoxes.config();
        boolean oldEnabled = c.pickupStorageEnabled,
                oldFull = c.preferEmptyBoxesOverInventory,
                oldAllow = c.allowPlayerSettings;
        var oldSpace = c.makeSpaceMode;
        try {
            c.pickupStorageEnabled = true;
            c.preferEmptyBoxesOverInventory = !overflow;
            c.allowPlayerSettings = false;
            c.makeSpaceMode = StorageConfig.MakeSpaceMode.DISABLED;
            test.run();
        } finally {
            c.pickupStorageEnabled = oldEnabled;
            c.preferEmptyBoxesOverInventory = oldFull;
            c.allowPlayerSettings = oldAllow;
            c.makeSpaceMode = oldSpace;
        }
    }

    private static void check(GameTestHelper h, boolean value, String message) {
        h.assertTrue(value, Component.literal(message));
    }
}
