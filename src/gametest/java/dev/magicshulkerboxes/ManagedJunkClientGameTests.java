package dev.magicshulkerboxes;

import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Real client/server clicks verify item roles, UI persistence and optional IPN sorting. */
public class ManagedJunkClientGameTests implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        try (var world = context.worldBuilder().create()) {
            var server = world.getServer();
            server.runOnServer(actual -> {
                var config = MagicShulkerBoxes.config();
                config.pickupStorageEnabled = true; config.allowPlayerSettings = false;
                var player = actual.getPlayerList().getPlayers().getFirst();
                player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
                player.getInventory().clearContent();
                player.getInventory().setItem(9, new ItemStack(Items.SHULKER_BOX));
                ShulkerStorage.store(player.getInventory(), new ItemStack(Items.STONE, 5), config);
                var chest = TestContainers.chest(player);
                player.openMenu(new net.minecraft.world.SimpleMenuProvider((id, inventory, owner) -> net.minecraft.world.inventory.ChestMenu.threeRows(id, inventory, chest), net.minecraft.network.chat.Component.literal("Managed exit test")));
                player.containerMenu.broadcastChanges();
            });
            context.waitFor(client -> ManagedJunkBoxes.marked(client.player.getInventory().getItem(9)) && client.player.containerMenu instanceof net.minecraft.world.inventory.ChestMenu);
            context.waitTicks(3);
            context.takeScreenshot("managed-junk-external-menu");
            context.runOnClient(client -> client.gameMode.handleInventoryMouseClick(client.player.containerMenu.containerId,
                    inventorySlot(client.player.containerMenu, client.player.getInventory(), 9), 0, ClickType.PICKUP, client.player));
            context.waitFor(client -> !client.player.containerMenu.getCarried().isEmpty()
                    && !ManagedJunkBoxes.marked(client.player.containerMenu.getCarried()));
            context.runOnClient(client -> client.gameMode.handleInventoryMouseClick(client.player.containerMenu.containerId,
                    inventorySlot(client.player.containerMenu, client.player.getInventory(), 18), 0, ClickType.PICKUP, client.player));
            context.waitFor(client -> !client.player.getInventory().getItem(18).isEmpty()
                    && !ManagedJunkBoxes.marked(client.player.getInventory().getItem(18)));
            // Click prediction can satisfy the client predicate before the server processes the ordered packets.
            context.waitTicks(10);
            server.runOnServer(actual -> {
                var player = actual.getPlayerList().getPlayers().getFirst();
                if (ManagedJunkBoxes.marked(player.getInventory().getItem(18)) || ManagedJunkGameTests.count(player.getInventory().getItem(18)) != 5)
                    throw new AssertionError("Native network clicks did not preserve contents and clear the role: "
                            + player.getInventory().getItem(18) + ", carried=" + player.inventoryMenu.getCarried());
            });
            server.runOnServer(actual -> actual.getPlayerList().getPlayers().getFirst().closeContainer());
            context.waitTicks(5);
            context.runOnClient(client -> client.setScreen(null));
            if (FabricLoader.getInstance().isModLoaded("yet_another_config_lib_v3")) settingsFlow(context, server);
            if (FabricLoader.getInstance().isModLoaded("inventoryprofilesnext")) sortingFlow(context, server);
        }
    }

    private static void settingsFlow(ClientGameTestContext context, TestServerContext server) {
        server.runOnServer(actual -> {
            var config = MagicShulkerBoxes.config();
            config.allowPlayerSettings = true;
            config.playerEditableSettings = List.of("useHotbarForSpace", "protectHotbarSlot1", "protectHotbarSlot9");
            SettingsNetwork.broadcastPolicy(actual);
        });
        context.waitTicks(20);
        context.setScreen(() -> dev.magicshulkerboxes.client.SettingsGui.personal(null));
        context.waitTicks(3);
        context.runOnClient(client -> {
            try {
                var field = client.screen.getClass().getDeclaredField("options"); field.setAccessible(true);
                var type = Class.forName("dev.isxander.yacl3.api.Option");
                int available = 0;
                for (var option : (List<?>) field.get(client.screen)) {
                    if (!(boolean) type.getMethod("available").invoke(option)) continue;
                    available++;
                    type.getMethod("requestSet", Object.class).invoke(option, dev.magicshulkerboxes.client.SettingsDraft.Toggle.ON);
                }
                if (available != 3) throw new AssertionError("Exactly the hotbar switch and two authorized protection positions should be editable");
            } catch (ReflectiveOperationException exception) { throw new AssertionError(exception); }
        });
        context.runOnClient(client -> client.screen.mouseScrolled(150, 150, 0, -200));
        context.waitTicks(3); context.takeScreenshot("managed-hotbar-protection");
        context.runOnClient(client -> {
            for (var child : client.screen.children()) {
                if (child instanceof net.minecraft.client.gui.components.Button button
                        && button.getMessage().getString().equals(net.minecraft.network.chat.Component.translatable("yacl.gui.save").getString())) {
                    button.onPress(new net.minecraft.client.input.KeyEvent(257, 0, 0));
                    return;
                }
            }
            throw new AssertionError("The native save button was not found");
        });
        context.waitTicks(15);
        server.runOnServer(actual -> {
            var player = actual.getPlayerList().getPlayers().getFirst();
            var config = MagicShulkerBoxes.configFor(player);
            if (!config.useHotbarForSpace || !config.protectHotbarSlot1 || !config.protectHotbarSlot9 || config.protectHotbarSlot2)
                throw new AssertionError("GUI did not save the exact selected positions through the server policy");
            if (BoxRelocation.canDisplace(new ItemStack(Items.STONE), 0, config)
                    || BoxRelocation.canDisplace(new ItemStack(Items.STONE), 8, config)
                    || !BoxRelocation.canDisplace(new ItemStack(Items.STONE), 1, config))
                throw new AssertionError("Server relocation did not enforce saved GUI positions");
        });
        context.setScreen(() -> dev.magicshulkerboxes.client.SettingsGui.personal(null));
        context.waitTicks(3); context.takeScreenshot("managed-hotbar-protection-reopened");
        context.runOnClient(client -> client.setScreen(null));
    }

    private static void sortingFlow(ClientGameTestContext context, TestServerContext server) {
        server.runOnServer(actual -> {
            var player = actual.getPlayerList().getPlayers().getFirst();
            player.getInventory().clearContent();
            player.getInventory().setItem(10, new ItemStack(Items.DIRT, 64));
            player.getInventory().setItem(11, new ItemStack(Items.STONE, 12));
            player.getInventory().setItem(35, ManagedJunkGameTests.managed());
            player.containerMenu.broadcastChanges();
        });
        context.waitFor(client -> ManagedJunkBoxes.marked(client.player.getInventory().getItem(35)));
        context.setScreen(() -> new InventoryScreen(net.minecraft.client.Minecraft.getInstance().player));
        context.runOnClient(client -> {
            try {
                var type = Class.forName("org.anti_ad.mc.ipnext.inventory.GeneralInventoryActions");
                type.getMethod("doSort", net.minecraft.world.inventory.AbstractContainerMenu.class, boolean.class, boolean.class)
                        .invoke(type.getField("INSTANCE").get(null), client.player.containerMenu, false, false);
            } catch (ReflectiveOperationException exception) { throw new AssertionError(exception); }
        });
        context.waitTicks(80);
        server.runOnServer(actual -> {
            var inventory = actual.getPlayerList().getPlayers().getFirst().getInventory();
            int managed = 0, stone = 0, dirt = 0, boxes = 0;
            for (int slot = 0; slot < 36; slot++) {
                var stack = inventory.getItem(slot);
                if (ShulkerStorage.isShulker(stack)) {
                    boxes += stack.getCount();
                    stone += ManagedJunkGameTests.count(stack);
                    if (ManagedJunkBoxes.marked(stack)) managed++;
                } else if (stack.is(Items.STONE)) stone += stack.getCount();
                else if (stack.is(Items.DIRT)) dirt += stack.getCount();
            }
            if (boxes != 1 || stone != 17 || dirt != 64 || managed != 1)
                throw new AssertionError("Actual IPN sorting changed quantities or lost the internally moved managed role: " + managed);
        });
        context.takeScreenshot("managed-junk-ipn-sorted");
        context.runOnClient(client -> client.setScreen(null));
    }

    private static int inventorySlot(net.minecraft.world.inventory.AbstractContainerMenu menu, net.minecraft.world.entity.player.Inventory inventory, int index) {
        for (int slot = 0; slot < menu.slots.size(); slot++)
            if (menu.slots.get(slot).container == inventory && menu.slots.get(slot).getContainerSlot() == index) return slot;
        throw new AssertionError("Native inventory slot not found");
    }
}
