package dev.magicshulkerboxes;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.ItemContainerContents;

/** Proves the optional integration does not make IPN or Kotlin a client startup requirement. */
public class ClientSmokeGameTests implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        if (FabricLoader.getInstance().isModLoaded("inventoryprofilesnext"))
            throw new AssertionError("Smoke test needs a client without IPN");
        try (var world = context.worldBuilder().create()) {
            context.runOnClient(
                    client -> {
                        if (client.player == null
                                || !ClientPlayNetworking.canSend(RestockNetwork.Request.ID)) {
                            throw new AssertionError(
                                    "Client without IPN could not join the modded integrated"
                                        + " server");
                        }
                    });
            world.getServer()
                    .runOnServer(
                            server -> {
                                var c = MagicShulkerBoxes.config();
                                c.pickupStorageEnabled = true;
                                c.preferEmptyBoxesOverInventory = true;
                                c.allowPlayerSettings = false;
                                var p = server.getPlayerList().getPlayers().getFirst();
                                p.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
                                p.getInventory().clearContent();
                                var box = new ItemStack(Items.SHULKER_BOX);
                                box.set(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
                                p.getInventory().setItem(9, box);
                                var chest = new net.minecraft.world.SimpleContainer(27);
                                chest.setItem(0, new ItemStack(Items.STONE, 8));
                                chest.setItem(1, new ItemStack(Items.STONE, 3));
                                p.openMenu(
                                        new net.minecraft.world.SimpleMenuProvider(
                                                (id, inv, owner) ->
                                                        net.minecraft.world.inventory.ChestMenu
                                                                .threeRows(id, inv, chest),
                                                net.minecraft.network.chat.Component.literal(
                                                        "Storage test")));
                            });
            context.waitFor(
                    client ->
                            client.player.containerMenu
                                            instanceof net.minecraft.world.inventory.ChestMenu
                                    && client.player.containerMenu.getSlot(0).getItem().getCount()
                                            == 8);
            context.runOnClient(
                    client ->
                            client.gameMode.handleInventoryMouseClick(
                                    client.player.containerMenu.containerId,
                                    0,
                                    0,
                                    net.minecraft.world.inventory.ClickType.QUICK_MOVE,
                                    client.player));
            context.waitFor(
                    client ->
                            stored(client.player.getInventory().getItem(9)) == 8
                                    && client.player.containerMenu.getSlot(0).getItem().isEmpty());
            context.runOnClient(
                    client ->
                            client.gameMode.handleInventoryMouseClick(
                                    client.player.containerMenu.containerId,
                                    1,
                                    0,
                                    net.minecraft.world.inventory.ClickType.PICKUP,
                                    client.player));
            context.waitFor(client -> client.player.containerMenu.getCarried().getCount() == 3);
            context.runOnClient(
                    client -> {
                        if (stored(client.player.getInventory().getItem(9)) != 8)
                            throw new AssertionError("Cursor was prematurely collected");
                        client.gameMode.handleInventoryMouseClick(
                                client.player.containerMenu.containerId,
                                28,
                                0,
                                net.minecraft.world.inventory.ClickType.PICKUP,
                                client.player);
                    });
            context.waitFor(
                    client ->
                            stored(client.player.getInventory().getItem(9)) == 11
                                    && client.player.containerMenu.getCarried().isEmpty());
            world.getServer()
                    .runOnServer(
                            server -> {
                                var p = server.getPlayerList().getPlayers().getFirst();
                                if (stored(p.getInventory().getItem(9)) != 11)
                                    throw new AssertionError("Server did not confirm stored items");
                                p.closeContainer();
                            });
            potionStorageFlow(context, world.getServer());
            if (FabricLoader.getInstance().isModLoaded("yet_another_config_lib_v3")) {
                world.getServer()
                        .runOnServer(
                                server -> {
                                    var c = MagicShulkerBoxes.config();
                                    c.allowPlayerSettings = true;
                                    c.ipnRefill = true;
                                    c.playerEditableSettings = java.util.List.of("ipnRefill");
                                    SettingsNetwork.broadcastPolicy(server);
                                });
                context.waitTicks(10);
                context.setScreen(() -> dev.magicshulkerboxes.client.SettingsGui.personal(null));
                context.waitTicks(3);
                context.takeScreenshot("settings-partial-permissions");
                context.runOnClient(
                        client -> {
                            try {
                                var field = client.screen.getClass().getDeclaredField("options");
                                field.setAccessible(true);
                                var options = (java.util.List<?>) field.get(client.screen);
                                var type = Class.forName("dev.isxander.yacl3.api.Option");
                                var available = type.getMethod("available");
                                Object permitted = null;
                                int count = 0;
                                for (var option : options)
                                    if ((boolean) available.invoke(option)) {
                                        count++;
                                        permitted = option;
                                    }
                                if (count != 1)
                                    throw new AssertionError(
                                            "Expected exactly one editable field, got " + count);
                                type.getMethod("requestSet", Object.class)
                                        .invoke(
                                                permitted,
                                                dev.magicshulkerboxes.client.SettingsDraft.Toggle
                                                        .OFF);
                                var save = client.screen.getClass().getMethod("finishOrSave");
                                save.setAccessible(true);
                                save.invoke(client.screen);
                            } catch (ReflectiveOperationException e) {
                                throw new AssertionError(
                                        "GUI field permission/save check failed", e);
                            }
                        });
                for (int i = 0; i < 200; i++) {
                    if (world.getServer()
                            .computeOnServer(
                                    server ->
                                            !MagicShulkerBoxes.configFor(
                                                            server.getPlayerList()
                                                                    .getPlayers()
                                                                    .getFirst())
                                                    .ipnRefill)) break;
                    context.waitTick();
                }
                world.getServer()
                        .runOnServer(
                                server -> {
                                    if (MagicShulkerBoxes.configFor(
                                                    server.getPlayerList().getPlayers().getFirst())
                                            .ipnRefill)
                                        throw new AssertionError(
                                                "GUI save was not confirmed by server");
                                });
                context.runOnClient(
                        client -> {
                            client.getLanguageManager().setSelected("zh_cn");
                            client.options.languageCode = "zh_cn";
                            client.reloadResourcePacks();
                        });
                context.waitFor(
                        client ->
                                client.getOverlay() == null && net.minecraft.locale.Language.getInstance()
                                        .getOrDefault("magic_shulker_boxes.gui.personal")
                                        .contains("个人"));
                context.setScreen(() -> dev.magicshulkerboxes.client.SettingsGui.personal(null));
                context.waitTicks(3);
                context.takeScreenshot("settings-partial-permissions-zh");
                world.getServer()
                        .runOnServer(
                                server -> {
                                    MagicShulkerBoxes.config().playerEditableSettings =
                                            java.util.List.of();
                                    SettingsNetwork.broadcastPolicy(server);
                                });
                context.waitTicks(10);
                context.runOnClient(
                        client -> {
                            try {
                                var field = client.screen.getClass().getDeclaredField("options");
                                field.setAccessible(true);
                                var available =
                                        Class.forName("dev.isxander.yacl3.api.Option")
                                                .getMethod("available");
                                for (var option : (java.util.List<?>) field.get(client.screen))
                                    if ((boolean) available.invoke(option))
                                        throw new AssertionError(
                                                "Revoked GUI kept an editable control");
                            } catch (ReflectiveOperationException e) {
                                throw new AssertionError("GUI revocation check failed", e);
                            }
                            for (var child : client.screen.children())
                                if (child
                                                instanceof
                                                net.minecraft.client.gui.components.Button button
                                        && button.getMessage()
                                                .getString()
                                                .equals(Messages.pattern("zh_cn", "gui.world"))) {
                                    button.onPress(new net.minecraft.client.input.KeyEvent(257, 0, 0));
                                    break;
                                }
                        });
                context.waitTicks(3);
                context.takeScreenshot("settings-world-permissions-zh");
                context.runOnClient(
                        client -> {
                            try {
                                var field = client.screen.getClass().getDeclaredField("options");
                                field.setAccessible(true);
                                if (((java.util.List<?>) field.get(client.screen)).size()
                                        != 2 * ConfigFile.optionNames().size() + 1)
                                    throw new AssertionError(
                                            "World settings lacks per-option permission controls");
                            } catch (ReflectiveOperationException e) {
                                throw new AssertionError("Local permission editor check failed", e);
                            }
                        });
                context.runOnClient(client -> client.screen.mouseScrolled(150, 150, 0, -200));
                context.waitTicks(3);
                context.takeScreenshot("settings-world-permissions-controls-zh");
                context.runOnClient(client -> client.setScreen(null));
            }
        }
    }

    private static void potionStorageFlow(
            ClientGameTestContext context,
            net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext world) {
        var strength = net.minecraft.world.item.alchemy.PotionContents.createItemStack(
                Items.POTION, net.minecraft.world.item.alchemy.Potions.STRENGTH);
        var speed = net.minecraft.world.item.alchemy.PotionContents.createItemStack(
                Items.POTION, net.minecraft.world.item.alchemy.Potions.SWIFTNESS);
        for (boolean full : new boolean[] {false, true}) {
            world.runOnServer(server -> {
                var config = MagicShulkerBoxes.config();
                config.preferEmptyBoxesOverInventory = false;
                config.matchItemComponents = false;
                config.makeSpaceMode = StorageConfig.MakeSpaceMode.DISABLED;
                var player = server.getPlayerList().getPlayers().getFirst();
                player.closeContainer();
                var inventory = player.getInventory();
                inventory.clearContent();
                if (full) for (int slot = 0; slot < 36; slot++) inventory.setItem(slot, new ItemStack(Items.STONE, 64));
                var source = new ItemStack(Items.SHULKER_BOX);
                source.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(java.util.List.of(strength.copy())));
                inventory.setItem(9, source);
                if (full) {
                    var empty = new ItemStack(Items.SHULKER_BOX);
                    empty.set(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
                    inventory.setItem(10, empty);
                }
                var chest = new net.minecraft.world.SimpleContainer(27);
                chest.setItem(0, speed.copy());
                player.openMenu(new net.minecraft.world.SimpleMenuProvider((id, inv, owner) ->
                        net.minecraft.world.inventory.ChestMenu.threeRows(id, inv, chest),
                        net.minecraft.network.chat.Component.literal("Potion classification test")));
                player.inventoryMenu.broadcastChanges();
            });
            context.waitFor(client -> client.player.containerMenu instanceof net.minecraft.world.inventory.ChestMenu
                    && ItemStack.matches(client.player.containerMenu.getSlot(0).getItem(), speed));
            context.runOnClient(client -> client.gameMode.handleInventoryMouseClick(
                    client.player.containerMenu.containerId, 0, 0,
                    net.minecraft.world.inventory.ClickType.QUICK_MOVE, client.player));
            // Wait for the authoritative chest update before checking results; client prediction is insufficient.
            boolean accepted = false;
            for (int tick = 0; tick < 200; tick++) {
                if (world.computeOnServer(server -> server.getPlayerList().getPlayers().getFirst()
                        .containerMenu.getSlot(0).getItem().isEmpty())) { accepted = true; break; }
                context.waitTick();
            }
            if (!accepted) throw new AssertionError("Server did not process the potion Shift-click");
            world.runOnServer(server -> {
                var inventory = server.getPlayerList().getPlayers().getFirst().getInventory();
                var existing = inventory.getItem(9).get(DataComponents.CONTAINER).stream().filter(stack -> !stack.isEmpty()).toList();
                if (existing.size() != 1 || !ItemStack.matches(existing.getFirst(), strength))
                    throw new AssertionError("Different potion entered the strength box over the real connection");
                var delivered = full ? inventory.getItem(10).get(DataComponents.CONTAINER).stream()
                        .filter(stack -> !stack.isEmpty()).findFirst().orElse(ItemStack.EMPTY)
                        : java.util.stream.IntStream.range(0, 36).mapToObj(inventory::getItem)
                                .filter(stack -> ItemStack.isSameItemSameComponents(stack, speed)).findFirst().orElse(ItemStack.EMPTY);
                if (!ItemStack.matches(delivered, speed)) throw new AssertionError("Speed potion was not conserved in its separate destination");
            });
            context.waitFor(client -> stored(client.player.getInventory().getItem(9)) == 1
                    && (full ? stored(client.player.getInventory().getItem(10)) == 1
                    : java.util.stream.IntStream.range(0, 36).anyMatch(slot -> ItemStack.matches(client.player.getInventory().getItem(slot), speed))));
            world.runOnServer(server -> server.getPlayerList().getPlayers().getFirst().closeContainer());
            context.waitFor(client -> client.player.containerMenu == client.player.inventoryMenu);
        }
    }

    private static int stored(ItemStack box) {
        return box.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).stream()
                .mapToInt(ItemStack::getCount)
                .sum();
    }
}
