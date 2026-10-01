package dev.magicshulkerboxes;

import java.util.List;
import java.util.Properties;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.GameType;

/**
 * Uses a real loopback TCP connection to a DedicatedServer, independently of integrated-server
 * tests.
 */
public class DedicatedClientGameTests implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        var eula = java.nio.file.Path.of("eula.txt");
        try {
            if (Boolean.getBoolean("msb.test.acceptEula"))
                java.nio.file.Files.writeString(eula, "eula=true\n");
            if (!java.nio.file.Files.exists(eula)
                    || !java.nio.file.Files.readString(eula).contains("eula=true"))
                throw new AssertionError(
                        "Dedicated tests require Minecraft EULA acceptance; use"
                            + " -PacceptMinecraftEula if you agree");
        } catch (java.io.IOException exception) {
            throw new AssertionError("Could not prepare dedicated test EULA", exception);
        }
        var properties = new Properties();
        properties.setProperty("server-ip", "127.0.0.1");
        properties.setProperty("level-name", "msb-dedicated-test-world");
        try (var server = context.worldBuilder().createServer(properties);
                var connection = server.connect()) {
            context.runOnClient(
                    client -> {
                        check(
                                client.getSingleplayerServer() == null,
                                "Client is connected to a dedicated server");
                        check(
                                ClientPlayNetworking.canSend(RefillNetwork.Request.ID),
                                "Dedicated server supports the current protocol");
                    });
            server.runOnServer(
                    actual -> {
                        check(actual.isDedicatedServer(), "Actual dedicated server implementation");
                        MagicShulkerBoxes.LOGGER.info(
                                "Dedicated TCP acceptance server listening on loopback port {}",
                                actual.getPort());
                        actual.getPlayerList()
                                .getPlayers()
                                .getFirst()
                                .setGameMode(GameType.SURVIVAL);
                    });
            if (FabricLoader.getInstance().isModLoaded("inventoryprofilesnext")) {
                try {
                    Class.forName("dev.magicshulkerboxes.IpnClientGameTests")
                            .getMethod(
                                    "runFlows",
                                    ClientGameTestContext.class,
                                    TestServerContext.class)
                            .invoke(null, context, server);
                } catch (ReflectiveOperationException exception) {
                    throw new AssertionError("Dedicated IPN regression failed", exception);
                }
            }
            containersAndTrades(context, server);
            enderAndCrafting(context, server);
            policy(context, server);
            context.takeScreenshot("dedicated-tcp-acceptance");
        }
    }

    private static void containersAndTrades(
            ClientGameTestContext context, TestServerContext server) {
        server.runOnServer(
                actual -> {
                    var c = MagicShulkerBoxes.config();
                    c.pickupStorageEnabled = true;
                    c.onlyWhenInventoryFull = false;
                    c.allowPlayerSettings = false;
                    var p = actual.getPlayerList().getPlayers().getFirst();
                    p.getInventory().clearContent();
                    p.getInventory().setItem(9, box(ItemStack.EMPTY));
                    var chest = new SimpleContainer(27);
                    chest.setItem(0, new ItemStack(Items.STONE, 8));
                    p.openMenu(
                            new SimpleMenuProvider(
                                    (id, inv, owner) -> ChestMenu.threeRows(id, inv, chest),
                                    Component.literal("TCP storage")));
                });
        context.waitFor(
                client ->
                        client.player.containerMenu instanceof ChestMenu
                                && client.player.containerMenu.getSlot(0).getItem().getCount()
                                        == 8);
        context.runOnClient(
                client ->
                        client.gameMode.handleInventoryMouseClick(
                                client.player.containerMenu.containerId,
                                0,
                                0,
                                ClickType.QUICK_MOVE,
                                client.player));
        context.waitFor(
                client ->
                        stored(client.player.getInventory().getItem(9)) == 8
                                && client.player.containerMenu.getSlot(0).getItem().isEmpty());
        context.runOnClient(client -> client.player.closeContainer());
        context.waitFor(client -> client.player.containerMenu == client.player.inventoryMenu);
        waitForServer(
                context,
                server,
                actual ->
                        actual.getPlayerList().getPlayers().getFirst().containerMenu
                                == actual.getPlayerList().getPlayers().getFirst().inventoryMenu);
        server.runOnServer(
                actual -> {
                    var p = actual.getPlayerList().getPlayers().getFirst();
                    check(
                            stored(p.getInventory().getItem(9)) == 8,
                            "Dedicated server confirms container storage");
                    var trader = new Villager(EntityType.VILLAGER, p.level());
                    trader.setNoAi(true);
                    trader.setPos(p.getX() + 1, p.getY(), p.getZ());
                    p.level().addFreshEntity(trader);
                    trader.setTradingPlayer(p);
                    var offer =
                            new MerchantOffer(
                                    new ItemCost(Items.EMERALD, 1),
                                    new ItemStack(Items.STONE, 4),
                                    10,
                                    3,
                                    0);
                    var offers = new MerchantOffers();
                    offers.add(offer);
                    trader.setOffers(offers);
                    p.openMenu(
                            new SimpleMenuProvider(
                                    (id, inv, owner) -> new MerchantMenu(id, inv, trader),
                                    Component.literal("TCP trade")));
                    var menu = (MerchantMenu) p.containerMenu;
                    p.sendMerchantOffers(menu.containerId, offers, 1, 0, true, true);
                });
        context.waitFor(
                client ->
                        client.player.containerMenu instanceof MerchantMenu menu
                                && !menu.getOffers().isEmpty());
        server.runOnServer(
                actual -> {
                    var menu = actual.getPlayerList().getPlayers().getFirst().containerMenu;
                    menu.getSlot(0).set(new ItemStack(Items.EMERALD, 3));
                    menu.slotsChanged(menu.getSlot(0).container);
                    menu.broadcastChanges();
                });
        context.waitFor(
                client ->
                        client.player.containerMenu instanceof MerchantMenu
                                && client.player.containerMenu.getSlot(2).getItem().getCount()
                                        == 4);
        context.runOnClient(
                client ->
                        client.gameMode.handleInventoryMouseClick(
                                client.player.containerMenu.containerId,
                                2,
                                0,
                                ClickType.QUICK_MOVE,
                                client.player));
        context.waitFor(
                client ->
                        stored(client.player.getInventory().getItem(9)) == 20
                                && client.player.containerMenu.getSlot(0).getItem().isEmpty());
        server.runOnServer(
                actual -> {
                    var p = actual.getPlayerList().getPlayers().getFirst();
                    var menu = (MerchantMenu) p.containerMenu;
                    check(
                            stored(p.getInventory().getItem(9)) == 20
                                    && menu.getOffers().getFirst().getUses() == 3
                                    && menu.getTraderXp() == 9,
                            "Actual TCP Shift trades preserve result count, costs, uses and XP");
                });
        context.runOnClient(client -> client.player.closeContainer());
        waitForServer(
                context,
                server,
                actual ->
                        actual.getPlayerList().getPlayers().getFirst().containerMenu
                                == actual.getPlayerList().getPlayers().getFirst().inventoryMenu);
    }

    private static void enderAndCrafting(ClientGameTestContext context, TestServerContext server) {
        server.runOnServer(
                actual -> {
                    var p = actual.getPlayerList().getPlayers().getFirst();
                    p.getInventory().clearContent();
                    p.getEnderChestInventory().clearContent();
                    var c = MagicShulkerBoxes.config();
                    c.enderChestRefill = true;
                    c.schematicRefill = true;
                    c.craftRefill = true;
                    p.getEnderChestInventory().setItem(0, new ItemStack(Items.COBBLESTONE, 8));
                    SettingsNetwork.broadcastPolicy(actual);
                    p.inventoryMenu.broadcastChanges();
                });
        context.waitFor(
                client ->
                        client.player.containerMenu == client.player.inventoryMenu
                                && client.player.getInventory().isEmpty()
                                && client.player.getEnderChestInventory().getItem(0).getCount()
                                        == 8);
        context.runOnClient(
                client ->
                        ClientPlayNetworking.send(
                                new RefillNetwork.Request(
                                        100,
                                        -1,
                                        "minecraft:cobblestone",
                                        ItemFingerprint.of(
                                                client.player.getEnderChestInventory().getItem(0),
                                                client.player.registryAccess()))));
        context.waitFor(
                client ->
                        client.player.getInventory().getItem(9).is(Items.COBBLESTONE)
                                && client.player.getEnderChestInventory().getItem(0).isEmpty());
        var display =
                server.computeOnServer(
                        actual -> {
                            var p = actual.getPlayerList().getPlayers().getFirst();
                            check(
                                    p.getInventory().getItem(9).getCount() == 8,
                                    "Dedicated source extraction confirms all eight items");
                            p.getInventory().clearContent();
                            p.getEnderChestInventory()
                                    .setItem(0, new ItemStack(Items.OAK_PLANKS, 4));
                            p.getEnderChestInventory()
                                    .setItem(1, box(new ItemStack(Items.OAK_PLANKS, 4)));
                            var recipe =
                                    actual.getRecipeManager()
                                            .byKey(
                                                    ResourceKey.create(
                                                            Registries.RECIPE,
                                                            Identifier.withDefaultNamespace(
                                                                    "crafting_table")))
                                            .orElseThrow();
                            p.awardRecipes(List.of(recipe));
                            p.inventoryMenu.broadcastChanges();
                            var entries =
                                    new java.util.ArrayList<
                                            net.minecraft.world.item.crafting.display
                                                    .RecipeDisplayEntry>();
                            actual.getRecipeManager()
                                    .listDisplaysForRecipe(recipe.id(), entries::add);
                            return entries.getFirst().id();
                        });
        context.waitFor(
                client ->
                        client.player.getInventory().isEmpty()
                                && client.player
                                        .getEnderChestInventory()
                                        .getItem(0)
                                        .is(Items.OAK_PLANKS)
                                && stored(client.player.getEnderChestInventory().getItem(1)) == 4);
        context.runOnClient(client -> client.gameMode.handlePlaceRecipe(0, display, false));
        context.waitFor(
                client ->
                        client.player
                                .inventoryMenu
                                .getResultSlot()
                                .getItem()
                                .is(Items.CRAFTING_TABLE));
        context.runOnClient(
                client ->
                        client.gameMode.handleInventoryMouseClick(
                                0, 0, 0, ClickType.PICKUP, client.player));
        context.waitFor(
                client ->
                        client.player.inventoryMenu.getCarried().is(Items.CRAFTING_TABLE)
                                && stored(client.player.getEnderChestInventory().getItem(1)) == 0);
        server.runOnServer(
                actual -> {
                    var p = actual.getPlayerList().getPlayers().getFirst();
                    check(
                            p.getEnderChestInventory().getItem(0).isEmpty()
                                    && stored(p.getEnderChestInventory().getItem(1)) == 0
                                    && p.inventoryMenu.getInputGridSlots().stream()
                                            .allMatch(s -> s.getItem().getCount() == 1),
                            "Dedicated crafting uses exactly eight planks with complete pattern"
                                + " refill");
                });
    }

    private static void policy(ClientGameTestContext context, TestServerContext server) {
        server.runOnServer(
                actual -> {
                    var c = MagicShulkerBoxes.config();
                    c.allowPlayerSettings = true;
                    c.ipnRefill = true;
                    c.playerEditableSettings = List.of("ipnRefill");
                    SettingsNetwork.broadcastPolicy(actual);
                });
        context.waitTicks(25);
        context.runOnClient(
                client ->
                        ClientPlayNetworking.send(
                                new EditorNetwork.Save(77, "{\"pickupStorageEnabled\":false}")));
        context.waitTicks(25);
        server.runOnServer(
                actual -> {
                    var p = actual.getPlayerList().getPlayers().getFirst();
                    try {
                        check(
                                !MagicShulkerBoxes.players(actual)
                                        .read(p.getUUID())
                                        .has("pickupStorageEnabled"),
                                "TCP GUI request cannot change a locked field");
                    } catch (java.io.IOException exception) {
                        throw new AssertionError(
                                "Could not inspect dedicated preferences", exception);
                    }
                });
        context.runOnClient(
                client ->
                        ClientPlayNetworking.send(
                                new EditorNetwork.Save(78, "{\"ipnRefill\":false}")));
        waitForServer(
                context,
                server,
                actual ->
                        !MagicShulkerBoxes.configFor(actual.getPlayerList().getPlayers().getFirst())
                                .ipnRefill);
        server.runOnServer(
                actual -> {
                    MagicShulkerBoxes.config().playerEditableSettings = List.of();
                    check(
                            MagicShulkerBoxes.configFor(
                                            actual.getPlayerList().getPlayers().getFirst())
                                    .ipnRefill,
                            "Revocation immediately overrides the previously saved TCP choice");
                    SettingsNetwork.broadcastPolicy(actual);
                });
    }

    private static void waitForServer(
            ClientGameTestContext context,
            TestServerContext server,
            java.util.function.Predicate<net.minecraft.server.MinecraftServer> done) {
        for (int i = 0; i < 200; i++) {
            if (server.computeOnServer(done::test)) return;
            context.waitTick();
        }
        throw new AssertionError("Dedicated server did not confirm the request");
    }

    private static ItemStack box(ItemStack item) {
        var box = new ItemStack(Items.SHULKER_BOX);
        box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(item)));
        return box;
    }

    private static int stored(ItemStack box) {
        return box.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).stream()
                .mapToInt(ItemStack::getCount)
                .sum();
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
