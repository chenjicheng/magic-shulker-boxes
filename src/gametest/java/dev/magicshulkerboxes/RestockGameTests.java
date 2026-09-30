package dev.magicshulkerboxes;

import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.GameType;

public class RestockGameTests {
    @GameTest public void consumedPotionLeavesBottleForIpnToSwap(GameTestHelper helper) {
        var player = player(helper);
        player.getInventory().setItem(0, new ItemStack(Items.GLASS_BOTTLE));
        var request = request(player, 1);
        check(helper, RestockNetwork.accept(player, request) == 1, "Potion extracted into IPN-visible storage");
        check(helper, player.getInventory().getItem(10).is(Items.POTION), "IPN gets an actual backpack candidate");
        check(helper, player.getMainHandItem().is(Items.GLASS_BOTTLE), "IPN remains responsible for hand replacement");
        check(helper, stored(player).isEmpty(), "Source lost exactly one potion");
        helper.succeed();
    }

    @GameTest public void changedSourceAndChangedTargetAreBothRejected(GameTestHelper helper) {
        var changedSource = player(helper); var sourceRequest = request(changedSource, 1);
        changedSource.getInventory().getItem(9).set(DataComponents.CONTAINER,
                ItemContainerContents.fromItems(List.of(new ItemStack(Items.POTION).copyWithCount(1))));
        var potion = stored(changedSource).getFirst();
        potion.set(DataComponents.CUSTOM_NAME, Component.literal("Changed"));
        changedSource.getInventory().getItem(9).set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(potion)));
        check(helper, RestockNetwork.accept(changedSource, sourceRequest) == 0, "Changed source components refused");
        var changedTarget = player(helper); var targetRequest = request(changedTarget, 1);
        changedTarget.getInventory().setItem(0, new ItemStack(Items.DIAMOND));
        check(helper, RestockNetwork.accept(changedTarget, targetRequest) == 0, "Changed hand refused");
        check(helper, stored(changedSource).size() == 1 && stored(changedTarget).size() == 1, "Both sources retained");
        helper.succeed();
    }

    @GameTest public void duplicateAndRapidRequestsCannotKeepExtracting(GameTestHelper helper) {
        var player = player(helper); var request = request(player, 1);
        check(helper, RestockNetwork.accept(player, request) == 1, "First request accepted");
        check(helper, RestockNetwork.accept(player, request) == 0, "Duplicate rejected");
        player.getInventory().getItem(9).set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.POTION))));
        check(helper, RestockNetwork.accept(player, request(player, 2)) == 0, "Second request in same tick rate limited");
        check(helper, stored(player).size() == 1, "Rapid request kept source intact");
        helper.succeed();
    }

    @GameTest public void changedCountsAndTransientComponentsAreRejected(GameTestHelper helper) {
        var player = player(helper); var request = request(player, 1);
        player.getInventory().getItem(9).setCount(2);
        check(helper, RestockNetwork.accept(player, request) == 0, "Changed stacked-box count refused");
        var target = player(helper); target.getInventory().setItem(0, new ItemStack(Items.GLASS_BOTTLE));
        var targetRequest = request(target, 1); target.getInventory().getItem(0).grow(1);
        check(helper, RestockNetwork.accept(target, targetRequest) == 0, "Changed target count refused");
        var transientSource = player(helper); var oldRequest = request(transientSource, 1);
        var potion = stored(transientSource).getFirst(); potion.set(DataComponents.CREATIVE_SLOT_LOCK, net.minecraft.util.Unit.INSTANCE);
        transientSource.getInventory().getItem(9).set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(potion)));
        check(helper, RestockNetwork.accept(transientSource, oldRequest) == 0, "Transient source components refused");
        helper.succeed();
    }

    @GameTest public void noSpaceAndInvalidDestinationMaskAreAtomic(GameTestHelper helper) {
        var player = player(helper);
        player.getInventory().setItem(10, new ItemStack(Items.SHULKER_BOX));
        var request = request(player, 1);
        check(helper, RestockNetwork.accept(player, request) == 0, "Cannot overwrite a protected box");
        check(helper, stored(player).size() == 1, "Failed extraction retained potion");
        var other = player(helper); var valid = request(other, 1);
        var invalid = new RestockNetwork.Request(valid.request(), valid.boxSlot(), valid.contentSlot(), valid.boxCount(),
                valid.sourceCount(), valid.targetSlot(), valid.targetCount(), -1, valid.sourceFingerprint(), valid.targetFingerprint());
        check(helper, RestockNetwork.accept(other, invalid) == 0, "Invalid mask refused");
        check(helper, stored(other).size() == 1, "Invalid request retained potion");
        helper.succeed();
    }

    @GameTest public void creativeMenusAndCursorItemsDoNotAllowExtraction(GameTestHelper helper) {
        var creative = player(helper); creative.setGameMode(GameType.CREATIVE);
        check(helper, RestockNetwork.accept(creative, request(creative, 1)) == 0, "Creative refused");
        var cursor = player(helper); cursor.inventoryMenu.setCarried(new ItemStack(Items.DIRT));
        check(helper, RestockNetwork.accept(cursor, request(cursor, 1)) == 0, "Cursor item refused");
        var menu = player(helper); menu.containerMenu = new net.minecraft.world.inventory.ChestMenu(
                net.minecraft.world.inventory.MenuType.GENERIC_9x3, 1, menu.getInventory(), new net.minecraft.world.SimpleContainer(27), 3);
        check(helper, RestockNetwork.accept(menu, request(menu, 1)) == 0, "Open container refused");
        helper.succeed();
    }

    @GameTest public void switchingHotbarBeforeRequestArrivesCancelsExtraction(GameTestHelper helper) {
        var player = player(helper); var request = request(player, 1);
        player.getInventory().setSelectedSlot(1);
        check(helper, RestockNetwork.accept(player, request) == 0, "No refill after IPN's monitored hotbar slot changed");
        check(helper, stored(player).size() == 1, "Canceled trigger retained source potion");
        helper.succeed();
    }

    @GameTest public void independentDefaultsAndPersonalOverrideApply(GameTestHelper helper) throws Exception {
        var config = MagicShulkerBoxes.config();
        boolean oldIpn = config.ipnRefill, oldAllowed = config.allowPlayerSettings, oldSchematic = config.schematicRefill;
        try {
            config.ipnRefill = false; config.schematicRefill = false; config.allowPlayerSettings = false;
            var disabled = player(helper);
            check(helper, RestockNetwork.accept(disabled, request(disabled, 1)) == 0, "Server can disable source extension");
            config.allowPlayerSettings = true;
            var optedIn = player(helper);
            MagicShulkerBoxes.players(helper.getLevel().getServer()).save(optedIn.getUUID(),
                    ConfigFile.parsePreferences("{\"ipnRefill\":true}"));
            check(helper, RestockNetwork.accept(optedIn, request(optedIn, 1)) == 1, "Personal override enables IPN independently");
        } finally { config.ipnRefill = oldIpn; config.allowPlayerSettings = oldAllowed; config.schematicRefill = oldSchematic; }
        helper.succeed();
    }

    @SuppressWarnings("removal") private static ServerPlayer player(GameTestHelper helper) {
        var player = helper.makeMockServerPlayerInLevel(); player.setGameMode(GameType.SURVIVAL);
        var box = new ItemStack(Items.SHULKER_BOX);
        box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.POTION))));
        player.getInventory().setItem(9, box);
        return player;
    }
    private static RestockNetwork.Request request(ServerPlayer player, int id) {
        var source = stored(player).getFirst(); var target = player.getInventory().getItem(0);
        return new RestockNetwork.Request(id, 9, 0, 1, source.getCount(), 0, target.getCount(), 3,
                ItemFingerprint.of(source, player.registryAccess()), ItemFingerprint.of(target, player.registryAccess()));
    }
    private static List<ItemStack> stored(ServerPlayer player) {
        return player.getInventory().getItem(9).get(DataComponents.CONTAINER).stream().filter(stack -> !stack.isEmpty()).toList();
    }
    private static void check(GameTestHelper helper, boolean value, String reason) { helper.assertTrue(value, Component.literal(reason)); }
}
