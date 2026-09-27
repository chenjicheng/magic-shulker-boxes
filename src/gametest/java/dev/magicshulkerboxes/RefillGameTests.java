package dev.magicshulkerboxes;

import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.GameType;

public class RefillGameTests {
    @GameTest public void serverPrefersLeastFilledBoxEvenWhenClientRequestsFullBox(GameTestHelper helper) {
        var player = player(helper); var inventory = player.getInventory();
        var packed = new java.util.ArrayList<ItemStack>();
        for (int i = 0; i < 27; i++) packed.add(new ItemStack(Items.STONE, 64));
        var full = new ItemStack(Items.SHULKER_BOX);
        full.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(packed));
        inventory.setItem(0, full);
        var sparse = new ItemStack(Items.SHULKER_BOX);
        sparse.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.STONE))));
        inventory.setItem(35, sparse);
        check(helper, RefillNetwork.accept(player, request(helper, 0, 0, "minecraft:stone")) == 1,
                "Server chooses the least filled eligible box, independently of client ordering");
        check(helper, inventory.getItem(0).get(DataComponents.CONTAINER).stream().mapToInt(ItemStack::getCount).sum() == 1728,
                "The full box is preserved");
        check(helper, inventory.getItem(35).get(DataComponents.CONTAINER).stream().allMatch(ItemStack::isEmpty),
                "The sparse box is emptied first");
        helper.succeed();
    }

    @GameTest public void pickupOffDoesNotBlockRefillOrPersonalRefillOff(GameTestHelper helper) throws java.io.IOException {
        var config = MagicShulkerBoxes.config();
        boolean oldPickup = config.enabled, oldRefill = config.schematicRefill, oldPolicy = config.allowPlayerSettings;
        try {
            config.enabled = false;
            config.schematicRefill = true;
            config.allowPlayerSettings = true;
            var request = request(helper, 9, 0, "minecraft:stone");
            var allowed = player(helper);
            check(helper, RefillNetwork.accept(allowed, request) == 12, "Server pickup off must not block refill");
            var denied = player(helper);
            MagicShulkerBoxes.players(helper.getLevel().getServer()).save(denied.getUUID(),
                    ConfigFile.parsePreferences("{\"schematicRefill\":false}"));
            check(helper, RefillNetwork.accept(denied, request) == 0, "Personal refill off applies while pickup is off");
            check(helper, materials(denied) == 12, "Disabled refill preserves box contents");
            helper.succeed();
        } finally {
            config.enabled = oldPickup;
            config.schematicRefill = oldRefill;
            config.allowPlayerSettings = oldPolicy;
        }
    }

    @GameTest public void networkRequestUsesRealInventoryAndRejectsRepeats(GameTestHelper helper) {
        var player = player(helper);
        var request = request(helper, 9, 0, "minecraft:stone");
        check(helper, player.isAlive(), "Fixture is alive");
        // Mojang's mock overrides gameMode() to CREATIVE; its real server game-mode controller is configurable.
        check(helper, player.gameMode.getGameModeForPlayer() == GameType.SURVIVAL, "Fixture is a survival player");
        check(helper, player.containerMenu == player.inventoryMenu && player.inventoryMenu.getCarried().isEmpty(), "Fixture has ordinary inventory open");
        check(helper, MagicShulkerBoxes.configFor(player).schematicRefill, "Fixture enables refilling independently of pickup");
        check(helper, player.getInventory().findSlotMatchingItem(new ItemStack(Items.STONE)) < 0, "Fixture has no loose stone");
        int moved = RefillNetwork.accept(player, request);
        check(helper, moved == 12, "Real server inventory receives source materials; moved=" + moved);
        check(helper, player.getInventory().getItem(10).getCount() == 12, "Materials arrive in ordinary backpack slot");
        check(helper, RefillNetwork.accept(player, request) == 0, "Repeat request cannot duplicate contents");
        helper.succeed();
    }

    @GameTest public void disabledSpectatorAndForgedItemRequestsCannotExtract(GameTestHelper helper) {
        var request = request(helper, 9, 0, "minecraft:stone");
        var forged = player(helper);
        check(helper, RefillNetwork.accept(forged, request(helper, 9, 0, "minecraft:diamond")) == 0, "Client item claim is checked");
        for (var mode : List.of(GameType.SPECTATOR, GameType.CREATIVE)) {
            var other = player(helper); other.setGameMode(mode);
            check(helper, RefillNetwork.accept(other, request) == 0, "Game mode cannot extract: " + mode);
            check(helper, materials(other) == 12, "Rejected game mode preserves materials");
        }
        var player = player(helper);
        var config = MagicShulkerBoxes.config(); boolean old = config.schematicRefill;
        try {
            config.schematicRefill = false;
            check(helper, RefillNetwork.accept(player, request) == 0, "Server feature switch cannot be overridden");
        } finally { config.schematicRefill = old; }
        check(helper, materials(player) == 12 && materials(forged) == 12, "Rejected requests preserve materials");
        helper.succeed();
    }

    @GameTest public void openContainerCursorAndBadIndicesCannotExtract(GameTestHelper helper) {
        for (int slot : new int[]{-1, 36, 40, Integer.MAX_VALUE}) {
            var player = player(helper);
            check(helper, RefillNetwork.accept(player, request(helper, slot, 0, "minecraft:stone")) == 0, "Invalid box index refused");
            check(helper, materials(player) == 12, "Invalid index preserves materials");
        }
        var carried = player(helper);
        carried.inventoryMenu.setCarried(new ItemStack(Items.DIAMOND));
        check(helper, RefillNetwork.accept(carried, request(helper, 9, 0, "minecraft:stone")) == 0, "Cursor stack blocks refill");
        check(helper, materials(carried) == 12, "Cursor refusal preserves materials");
        helper.succeed();
    }

    @GameTest public void excludedSourceCannotTriggerFallback(GameTestHelper helper) {
        for (int slot : new int[]{36, 40}) {
            var player = player(helper);
            player.getInventory().setItem(slot, player.getInventory().getItem(9).copy());
            var config = MagicShulkerBoxes.config(); boolean old = config.includeOffhand;
            try {
                config.includeOffhand = false;
                check(helper, RefillNetwork.accept(player, request(helper, slot, 0, "minecraft:stone")) == 0,
                        "Excluded source must not trigger extraction from a different box");
            } finally { config.includeOffhand = old; }
            check(helper, materials(player) == 12, "Excluded source preserves the valid material box");
        }
        helper.succeed();
    }

    private static int materials(net.minecraft.server.level.ServerPlayer player) {
        return player.getInventory().getItem(9).get(DataComponents.CONTAINER).stream().mapToInt(ItemStack::getCount).sum();
    }

    @GameTest public void unusableStackedSourceDoesNotHideLaterUsableBox(GameTestHelper helper) {
        var player = player(helper); var inv = player.getInventory();
        var first = inv.getItem(9).copy();
        for (int i = 0; i < 36; i++) inv.setItem(i, new ItemStack(Items.DIRT, 64));
        inv.setItem(9, first); first.setCount(3);
        inv.setItem(10, first.copyWithCount(1));
        check(helper, RefillNetwork.accept(player, request(helper, 9, 0, "minecraft:stone")) == 12, "Later usable box can supply materials");
        check(helper, inv.getItem(9).getCount() == 3, "Unusable stacked source remains unchanged");
        check(helper, inv.getItem(11).is(Items.STONE), "Full inventory received materials safely");
        helper.succeed();
    }

    @SuppressWarnings("removal") private static net.minecraft.server.level.ServerPlayer player(GameTestHelper helper) {
        var player = helper.makeMockServerPlayerInLevel(); player.setGameMode(GameType.SURVIVAL);
        var box = new ItemStack(Items.SHULKER_BOX);
        box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.STONE, 12))));
        player.getInventory().setItem(9, box);
        return player;
    }
    private static void check(GameTestHelper helper, boolean value, String reason) { helper.assertTrue(value, Component.literal(reason)); }
    private static RefillNetwork.Request request(GameTestHelper helper, int boxSlot, int contentSlot, String item) {
        var stack = new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.parse(item)));
        return new RefillNetwork.Request(boxSlot, contentSlot, item, ItemFingerprint.of(stack, helper.getLevel().registryAccess()));
    }
}
