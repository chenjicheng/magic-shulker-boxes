package dev.magicshulkerboxes;

import java.util.List;
import java.util.UUID;
import java.lang.reflect.Method;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.GameType;

public class PickupGameTests implements CustomTestMethodInvoker {
    @Override
    public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
        boolean pickup = MagicShulkerBoxes.config().enabled;
        try {
            // These fixtures test opted-in pickup; shipping defaults are tested separately.
            MagicShulkerBoxes.config().enabled = true;
            invokeWithCarpet(helper, method);
        } finally { MagicShulkerBoxes.config().enabled = pickup; }
    }

    private void invokeWithCarpet(GameTestHelper helper, Method method) throws ReflectiveOperationException {
        if (!FabricLoader.getInstance().isModLoaded("carpet")) {
            method.invoke(this, helper);
            return;
        }
        var rule = Class.forName("carpet.CarpetSettings").getField("shulkerBoxStackSize");
        int previous = rule.getInt(null);
        try {
            rule.setInt(null, 64);
            check(helper, box().getMaxStackSize() == 64, "Real Carpet stacking mixin must be active");
            method.invoke(this, helper);
        } finally {
            rule.setInt(null, previous);
        }
    }

    @GameTest
    public void fullInventoryStoresPickupAndRemovesEntity(GameTestHelper helper) {
        var player = fullPlayer(helper);
        player.getInventory().setItem(0, box(new ItemStack(Items.COBBLESTONE)));
        var drop = drop(helper, 5);
        drop.playerTouch(player);
        check(helper, countContents(player.getInventory().getItem(0)) == 6, "Overflow goes into matching box");
        check(helper, drop.isRemoved(), "Fully collected item entity is removed");
        helper.succeed();
    }

    @GameTest
    public void refillOffDoesNotBlockPickup(GameTestHelper helper) {
        var config = MagicShulkerBoxes.config();
        boolean previous = config.schematicRefill;
        try {
            config.schematicRefill = false;
            var player = fullPlayer(helper);
            player.getInventory().setItem(0, box(new ItemStack(Items.COBBLESTONE)));
            var dropped = drop(helper, 5);
            dropped.playerTouch(player);
            check(helper, dropped.isRemoved(), "Pickup still succeeds with refill disabled");
            check(helper, countContents(player.getInventory().getItem(0)) == 6, "Picked-up items enter the box");
            helper.succeed();
        } finally { config.schematicRefill = previous; }
    }

    @GameTest
    public void vanillaSlotsReceiveItemsBeforeShulkerBoxes(GameTestHelper helper) {
        var player = fullPlayer(helper);
        player.getInventory().setItem(0, box(new ItemStack(Items.COBBLESTONE)));
        player.getInventory().setItem(5, new ItemStack(Items.COBBLESTONE, 62));
        var drop = drop(helper, 5);
        drop.playerTouch(player);
        check(helper, player.getInventory().getItem(5).getCount() == 64, "Vanilla stack fills first");
        check(helper, countContents(player.getInventory().getItem(0)) == 4, "Only remainder enters shulker");
        check(helper, drop.isRemoved(), "All five items were collected");
        helper.succeed();
    }

    @GameTest
    public void partialPickupLeavesRemainderInWorld(GameTestHelper helper) {
        var player = fullPlayer(helper);
        var contents = new ItemStack[27];
        for (int i = 0; i < 27; i++) contents[i] = new ItemStack(Items.COBBLESTONE, 64);
        contents[26].setCount(62);
        player.getInventory().setItem(0, box(contents));
        var drop = drop(helper, 5);
        drop.playerTouch(player);
        check(helper, !drop.isRemoved() && drop.getItem().getCount() == 3, "Unstored items stay in world");
        check(helper, countContents(player.getInventory().getItem(0)) == 1728, "Only two items entered box");
        drop.playerTouch(player);
        check(helper, drop.getItem().getCount() == 3, "Repeated pickup never duplicates or deletes items");
        helper.succeed();
    }

    @GameTest
    public void pickupDelayAndOwnershipStillApply(GameTestHelper helper) {
        var player = fullPlayer(helper);
        player.getInventory().setItem(0, box());
        var drop = drop(helper, 5);
        drop.setDefaultPickUpDelay();
        drop.playerTouch(player);
        check(helper, countContents(player.getInventory().getItem(0)) == 0, "Pickup delay blocks storage");
        drop.setNoPickUpDelay();
        drop.setTarget(UUID.randomUUID());
        drop.playerTouch(player);
        check(helper, countContents(player.getInventory().getItem(0)) == 0, "Wrong owner blocks storage");
        check(helper, drop.getItem().getCount() == 5, "Rejected item is unchanged");
        drop.setTarget(player.getUUID());
        drop.playerTouch(player);
        check(helper, drop.isRemoved(), "Correct owner can collect");
        helper.succeed();
    }

    @GameTest
    public void stackedEmptyBoxWithNoSlotFallsBackToMixedBox(GameTestHelper helper) {
        var player = fullPlayer(helper);
        var stacked = box();
        stacked.setCount(16);
        player.getInventory().setItem(0, stacked);
        player.getInventory().setItem(1, box(new ItemStack(Items.STONE), new ItemStack(Items.DIRT)));
        var drop = drop(helper, 5);
        drop.playerTouch(player);
        check(helper, stacked.getCount() == 16 && countContents(stacked) == 0, "Stacked empty boxes stay unchanged");
        check(helper, countContents(player.getInventory().getItem(1)) == 7, "Mixed box receives overflow");
        check(helper, drop.isRemoved(), "Mixed storage collects entity");
        helper.succeed();
    }

    @GameTest
    public void boxesFirstModeSplitsOneBoxIntoFreeMainSlot(GameTestHelper helper) {
        var config = MagicShulkerBoxes.config();
        boolean previous = config.onlyWhenInventoryFull;
        try {
            config.onlyWhenInventoryFull = false;
            var player = fullPlayer(helper);
            var stacked = new ItemStack(Items.BLUE_SHULKER_BOX, 16);
            stacked.set(DataComponents.CUSTOM_NAME, Component.literal("Building blocks"));
            player.getInventory().setItem(0, stacked);
            player.getInventory().setItem(5, ItemStack.EMPTY);
            var drop = drop(helper, 5);
            drop.playerTouch(player);
            var filled = player.getInventory().getItem(5);
            check(helper, stacked.getCount() == 15 && countContents(stacked) == 0, "Empty remainder stays empty");
            check(helper, filled.is(Items.BLUE_SHULKER_BOX) && filled.getCount() == 1, "Exactly one box was split");
            check(helper, filled.getMaxStackSize() == 1, "Filled box cannot stack even with Carpet enabled");
            check(helper, filled.getHoverName().getString().equals("Building blocks"), "Custom name survives split");
            check(helper, countContents(filled) == 5 && drop.isRemoved(), "Pickup was stored in split box");
            helper.succeed();
        } finally {
            config.onlyWhenInventoryFull = previous;
        }
    }

    @GameTest
    public void freeVanillaSlotDoesNotSplitABoxInDefaultMode(GameTestHelper helper) {
        var player = fullPlayer(helper);
        var stacked = box();
        stacked.setCount(16);
        player.getInventory().setItem(0, stacked);
        player.getInventory().setItem(5, ItemStack.EMPTY);
        var drop = drop(helper, 5);
        drop.playerTouch(player);
        check(helper, stacked.getCount() == 16 && countContents(stacked) == 0, "No unnecessary split");
        check(helper, player.getInventory().getItem(5).is(Items.COBBLESTONE), "Empty main slot receives pickup");
        check(helper, player.getInventory().getItem(5).getCount() == 5 && drop.isRemoved(), "All items collected normally");
        helper.succeed();
    }

    @GameTest
    public void disabledConfigLeavesFullInventoryPickupOnGround(GameTestHelper helper) {
        var config = MagicShulkerBoxes.config();
        boolean previous = config.enabled;
        try {
            config.enabled = false;
            var player = fullPlayer(helper);
            player.getInventory().setItem(0, box());
            var drop = drop(helper, 5);
            drop.playerTouch(player);
            check(helper, !drop.isRemoved() && drop.getItem().getCount() == 5, "Disabled mod retains vanilla behavior");
            check(helper, countContents(player.getInventory().getItem(0)) == 0, "Box remains empty");
            helper.succeed();
        } finally {
            config.enabled = previous;
        }
    }

    @GameTest
    public void defaultMakesSpaceAndRepeatedMixedPickupsReuseOneBox(GameTestHelper helper) {
        var player = fullPlayer(helper);
        var stacked = new ItemStack(Items.BLUE_SHULKER_BOX, 16);
        stacked.set(DataComponents.CUSTOM_NAME, Component.literal("Auto space"));
        player.getInventory().setItem(0, stacked);
        player.getInventory().setItem(9, new ItemStack(Items.STONE, 3));
        var first = drop(helper, 5);
        first.playerTouch(player);
        var filled = player.getInventory().getItem(9);
        check(helper, stacked.getCount() == 15 && countContents(stacked) == 0, "Exactly one box split");
        check(helper, countContents(filled) == 8 && first.isRemoved(), "Partial inventory stack and pickup conserved");
        check(helper, filled.getHoverName().getString().equals("Auto space"), "Box name preserved");
        var second = new ItemEntity(helper.getLevel(), 0, 4, 0, new ItemStack(Items.GRAVEL, 7));
        second.setNoPickUpDelay();
        second.playerTouch(player);
        check(helper, stacked.getCount() == 15, "Mixed pickup reuses box without another split");
        check(helper, countContents(player.getInventory().getItem(9)) == 15 && second.isRemoved(), "Mixed items conserved");
        helper.succeed();
    }

    @GameTest
    public void dropModeRecollectsPartialDisplacedStackThroughRealPickup(GameTestHelper helper) {
        var config = MagicShulkerBoxes.config();
        var previous = config.makeSpaceMode;
        try {
            config.makeSpaceMode = StorageConfig.MakeSpaceMode.DROP_AND_PICKUP;
            var player = fullPlayer(helper);
            var stacked = box();
            stacked.setCount(16);
            player.getInventory().setItem(0, stacked);
            var moved = new ItemStack(Items.STONE, 3);
            moved.set(DataComponents.CUSTOM_NAME, Component.literal("Three stones"));
            player.getInventory().setItem(9, moved);
            var incoming = drop(helper, 5);
            incoming.playerTouch(player);
            var filled = player.getInventory().getItem(9);
            check(helper, stacked.getCount() == 15 && countContents(filled) == 8, "Drop and repick preserves all eight items");
            check(helper, filled.get(DataComponents.CONTAINER).stream().anyMatch(s -> s.getHoverName().getString().equals("Three stones") && s.getCount() == 3), "Displaced components preserved");
            check(helper, incoming.isRemoved(), "Original pickup collected");
            check(helper, helper.getLevel().getEntitiesOfClass(ItemEntity.class, player.getBoundingBox().inflate(3)).stream().noneMatch(e -> e.getTags().contains("magic_shulker_boxes:relocated")), "Recollected entity removed from world");
            helper.succeed();
        } finally {
            config.makeSpaceMode = previous;
        }
    }

    @GameTest
    public void rejectedRelocationCannotTriggerAnotherEvictionLoop(GameTestHelper helper) {
        var player = fullPlayer(helper);
        var stacked = box();
        stacked.setCount(16);
        player.getInventory().setItem(0, stacked);
        var incoming = drop(helper, 3);
        incoming.addTag("magic_shulker_boxes:relocated");
        incoming.playerTouch(player);
        incoming.playerTouch(player);
        check(helper, stacked.getCount() == 16, "A relocated entity never frees another slot");
        check(helper, !incoming.isRemoved() && incoming.getItem().getCount() == 3, "Uncollectable relocation stays on ground");
        helper.succeed();
    }

    @GameTest
    public void playerSettingsGateChangesActualPickupImmediately(GameTestHelper helper) throws java.io.IOException {
        var player = fullPlayer(helper);
        var config = MagicShulkerBoxes.config();
        boolean previous = config.allowPlayerSettings;
        var settings = MagicShulkerBoxes.players(helper.getLevel().getServer());
        try {
            player.getInventory().setItem(0, box());
            settings.save(player.getUUID(), ConfigFile.parsePreferences("{\"enabled\":false}"));
            config.allowPlayerSettings = false;
            var first = drop(helper, 5);
            first.playerTouch(player);
            check(helper, first.isRemoved(), "Server-unified settings override disabled personal preference");
            config.allowPlayerSettings = true;
            var second = drop(helper, 3);
            second.playerTouch(player);
            check(helper, !second.isRemoved() && second.getItem().getCount() == 3, "Personal off setting takes effect immediately");
            check(helper, countContents(player.getInventory().getItem(0)) == 5, "Personal-disabled pickup never changes box");
            helper.succeed();
        } finally {
            config.allowPlayerSettings = previous;
            settings.save(player.getUUID(), new com.google.gson.JsonObject());
        }
    }

    @GameTest
    public void ordinaryPlayersCanOnlyChangeTheirOwnAllowedSettings(GameTestHelper helper) throws Exception {
        var player = fullPlayer(helper);
        var config = MagicShulkerBoxes.config();
        boolean previous = config.allowPlayerSettings;
        var settings = MagicShulkerBoxes.players(helper.getLevel().getServer());
        var commands = helper.getLevel().getServer().getCommands().getDispatcher();
        var source = player.createCommandSourceStack().withPermission(net.minecraft.server.permissions.PermissionSet.NO_PERMISSIONS);
        try {
            config.allowPlayerSettings = false;
            check(helper, commands.execute("msb set enabled false", source) == 0, "Server gate rejects personal edits");
            config.allowPlayerSettings = true;
            check(helper, commands.execute("msb set enabled false", source) == 1, "Ordinary player can set own preference");
            check(helper, !MagicShulkerBoxes.configFor(player).enabled, "Command affects effective settings");
            try {
                commands.execute("msb admin player-settings false", source);
                check(helper, false, "Ordinary player must not access admin branch");
            } catch (com.mojang.brigadier.exceptions.CommandSyntaxException expected) {
                check(helper, config.allowPlayerSettings, "Unauthorized command did not change server policy");
            }
            check(helper, commands.execute("msb reset", source) == 1, "Player can reset personal overrides");
            check(helper, MagicShulkerBoxes.configFor(player).enabled, "Reset inherits server configuration");
            helper.succeed();
        } finally {
            config.allowPlayerSettings = previous;
            settings.save(player.getUUID(), new com.google.gson.JsonObject());
        }
    }

    @GameTest
    public void adminPolicyAndNetworkInputRemainServerControlled(GameTestHelper helper) throws Exception {
        var player = fullPlayer(helper);
        boolean previous = MagicShulkerBoxes.config().allowPlayerSettings;
        var store = MagicShulkerBoxes.players(helper.getLevel().getServer());
        var commands = helper.getLevel().getServer().getCommands().getDispatcher();
        var source = player.createCommandSourceStack().withPermission(net.minecraft.server.permissions.PermissionSet.ALL_PERMISSIONS);
        try {
            check(helper, commands.execute("msb admin player-settings false", source) == 1, "Admin can disable personal settings");
            check(helper, !SettingsNetwork.accept(player, "{\"enabled\":false}"), "Forged sync is ignored while policy is disabled");
            check(helper, commands.execute("msb admin player-settings true", source) == 1, "Admin can enable personal settings");
            check(helper, SettingsNetwork.accept(player, "{\"enabled\":false}"), "Allowed network preference is accepted");
            check(helper, !MagicShulkerBoxes.configFor(player).enabled, "Network preference applies to sending player");
            try {
                SettingsNetwork.accept(player, "{\"allowPlayerSettings\":false}");
                check(helper, false, "A client cannot submit server policy");
            } catch (java.io.IOException expected) {
                check(helper, MagicShulkerBoxes.config().allowPlayerSettings, "Server policy unchanged");
            }
            check(helper, commands.execute("msb admin player-settings false", source) == 1, "Admin can revoke personal settings immediately");
            check(helper, MagicShulkerBoxes.configFor(player).enabled, "Server default resumes immediately after revocation");
            helper.succeed();
        } finally {
            store.save(player.getUUID(), new com.google.gson.JsonObject());
            MagicShulkerBoxes.allowPlayerSettings(previous);
        }
    }

    @GameTest
    public void guiRequestsAreValidatedAcknowledgedAndRateLimited(GameTestHelper helper) throws Exception {
        var player = fullPlayer(helper);
        var config = MagicShulkerBoxes.config();
        boolean previous = config.allowPlayerSettings;
        var store = MagicShulkerBoxes.players(helper.getLevel().getServer());
        try {
            config.allowPlayerSettings = false;
            check(helper, EditorNetwork.save(player, new EditorNetwork.Save(1, "{\"enabled\":false}")).status() == EditorNetwork.LOCKED, "GUI request obeys server policy");
            config.allowPlayerSettings = true;
            var result = EditorNetwork.save(player, new EditorNetwork.Save(2, "{\"enabled\":false}"));
            check(helper, result.status() == EditorNetwork.SAVED && result.request() == 2, "Saved reply identifies the exact request");
            check(helper, !MagicShulkerBoxes.configFor(player).enabled, "GUI save changes actual effective settings");
            check(helper, EditorNetwork.save(player, new EditorNetwork.Save(3, "{}")).status() == EditorNetwork.BUSY, "Repeated GUI saves report busy instead of silently dropping");
            var another = fullPlayer(helper);
            check(helper, EditorNetwork.save(another, new EditorNetwork.Save(4, "{\"allowPlayerSettings\":false}")).status() == EditorNetwork.INVALID, "GUI cannot change server policy");
            check(helper, store.read(another.getUUID()).isEmpty(), "Invalid edit preserves other player defaults");
            helper.succeed();
        } finally {
            config.allowPlayerSettings = previous;
            store.save(player.getUUID(), new com.google.gson.JsonObject());
        }
    }

    // 1.21.11 still exposes this helper; a real ServerPlayer exercises the pickup and inventory synchronization path.
    @SuppressWarnings("removal")
    private static ServerPlayer fullPlayer(GameTestHelper helper) {
        var player = helper.makeMockServerPlayerInLevel();
        player.setGameMode(GameType.SURVIVAL);
        for (int i = 0; i < 36; i++) player.getInventory().setItem(i, new ItemStack(Items.STONE, 64));
        return player;
    }

    private static ItemEntity drop(GameTestHelper helper, int count) {
        var drop = new ItemEntity(helper.getLevel(), 0, 4, 0, new ItemStack(Items.COBBLESTONE, count));
        drop.setNoPickUpDelay();
        return drop;
    }

    private static ItemStack box(ItemStack... contents) {
        var box = new ItemStack(Items.SHULKER_BOX);
        box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(contents)));
        return box;
    }

    private static int countContents(ItemStack box) {
        return box.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY)
                .stream().mapToInt(ItemStack::getCount).sum();
    }

    private static void check(GameTestHelper helper, boolean condition, String message) {
        helper.assertTrue(condition, Component.literal(message));
    }
}
