package dev.magicshulkerboxes;

import com.mojang.blaze3d.platform.InputConstants;
import dev.magicshulkerboxes.client.JunkSlotsClient;
import dev.magicshulkerboxes.mixin.JunkSlotScreenAccess;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import org.lwjgl.glfw.GLFW;

public class JunkSlotsClientGameTests implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        try (var world = context.worldBuilder().create()) {
            var server = world.getServer();
            server.runOnServer(actual -> {
                var player = actual.getPlayerList().getPlayers().getFirst();
                player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
                player.getInventory().clearContent();
                player.getInventory().setItem(9, box(new ItemStack(Items.STONE, 64), new ItemStack(Items.DIRT, 3)));
                player.getInventory().setItem(10, box());
                var cfg = MagicShulkerBoxes.config(); cfg.pickupStorageEnabled = true; cfg.preferEmptyBoxesOverInventory = true;
            });
            context.waitFor(client -> JunkSlotsClient.ready() && client.player.getInventory().getItem(9).is(Items.SHULKER_BOX));
            context.runOnClient(client -> {
                if (JunkSlotsClient.binding().getCategory().label().getString().startsWith("key.category"))
                    throw new AssertionError("The slot-selection key category is not translated in the actual controls UI");
                JunkSlotsClient.binding().setKey(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_F9));
                KeyMapping.resetMapping();
            });
            context.getInput().pressKey(options -> options.keyInventory);
            context.waitForScreen(InventoryScreen.class);
            move(context, 9); context.getInput().holdKey(GLFW.GLFW_KEY_F9);
            move(context, 10); move(context, 35); move(context, 9);
            verifyTooltipCoversMarker(context, "junk-slots-pending-preview", 0xFFF3CF69);
            context.getInput().releaseKey(GLFW.GLFW_KEY_F9);
            long wanted = (1L << 9) | (1L << 10) | (1L << 35);
            context.waitFor(client -> JunkSlotsClient.confirmedMask() == wanted);
            server.runOnServer(actual -> {
                var player = actual.getPlayerList().getPlayers().getFirst();
                if (MagicShulkerBoxes.configFor(player).junkBoxSlots != wanted
                        || player.getInventory().getItem(9).get(DataComponents.CONTAINER).stream().mapToInt(ItemStack::getCount).sum() != 67)
                    throw new AssertionError("Native key gesture did not persist slots without changing inventory");
            });
            move(context, 35); context.takeScreenshot("junk-slots-selected-empty-tooltip");
            verifyTooltipCoversMarker(context, "junk-slots-selected-box-tooltip", 0xFF65D9CB);
            verifyEmptyTooltip(context, server, "junk-slots-selected-empty-overlap");
            context.runOnClient(client -> {
                client.getLanguageManager().setSelected("zh_cn"); client.options.languageCode = "zh_cn";
                client.reloadResourcePacks();
            });
            context.waitFor(client -> client.getOverlay() == null);
            context.waitTicks(2);
            context.getInput().resizeWindow(1024, 600);
            move(context, 35); context.takeScreenshot("junk-slots-chinese-empty-tooltip");
            verifyTooltipCoversMarker(context, "junk-slots-chinese-box-tooltip", 0xFF65D9CB);
            verifyEmptyTooltip(context, server, "junk-slots-chinese-empty-overlap");
            if (FabricLoader.getInstance().isModLoaded("inventoryprofilesnext")) verifySorting(context, server);
            move(context, 9); context.getInput().holdKey(GLFW.GLFW_KEY_F9); move(context, 10); move(context, 35);
            context.getInput().releaseKey(GLFW.GLFW_KEY_F9);
            context.waitFor(client -> JunkSlotsClient.confirmedMask() == 0);
            context.takeScreenshot("junk-slots-cleared");
            context.runOnClient(client -> {
                JunkSlotsClient.binding().setKey(InputConstants.Type.MOUSE.getOrCreate(GLFW.GLFW_MOUSE_BUTTON_4));
                KeyMapping.resetMapping();
            });
            move(context, 10); context.getInput().holdMouse(GLFW.GLFW_MOUSE_BUTTON_4); move(context, 35);
            context.getInput().releaseMouse(GLFW.GLFW_MOUSE_BUTTON_4);
            context.waitFor(client -> JunkSlotsClient.confirmedMask() == ((1L << 10) | (1L << 35)));
            move(context, 10); context.getInput().holdMouse(GLFW.GLFW_MOUSE_BUTTON_4); move(context, 35);
            context.getInput().releaseMouse(GLFW.GLFW_MOUSE_BUTTON_4);
            context.waitFor(client -> JunkSlotsClient.confirmedMask() == 0);
            server.runOnServer(actual -> {
                try {
                    var player = actual.getPlayerList().getPlayers().getFirst();
                    if (MagicShulkerBoxes.junkSlots(actual).read(player.getUUID()).mask() != 0)
                        throw new AssertionError("Erase gesture was not saved");
                } catch (java.io.IOException exception) { throw new AssertionError(exception); }
            });
        }
    }

    private static void verifyEmptyTooltip(ClientGameTestContext context, TestServerContext server, String name) {
        server.runOnServer(actual -> {
            var player = actual.getPlayerList().getPlayers().getFirst();
            player.getInventory().setItem(9, ItemStack.EMPTY); player.containerMenu.broadcastChanges();
        });
        context.waitFor(client -> client.player.getInventory().getItem(9).isEmpty());
        verifyTooltipCoversMarker(context, name, 0xFF65D9CB);
        server.runOnServer(actual -> {
            var player = actual.getPlayerList().getPlayers().getFirst();
            player.getInventory().setItem(9, box(new ItemStack(Items.STONE, 64), new ItemStack(Items.DIRT, 3)));
            player.containerMenu.broadcastChanges();
        });
        context.waitFor(client -> client.player.getInventory().getItem(9).is(Items.SHULKER_BOX));
    }

    private static void verifyTooltipCoversMarker(ClientGameTestContext context, String name, int markerColor) {
        int[] sample = context.computeOnClient(client -> {
            var screen = (AbstractContainerScreen<?>) client.screen;
            var access = (JunkSlotScreenAccess) screen;
            var slot = screen.getMenu().slots.stream().filter(s -> s.container == client.player.getInventory()
                    && s.getContainerSlot() == 10).findFirst().orElseThrow();
            // Hovering slot 9 places its tooltip over the right end of slot 10's top-left marker.
            return new int[] {
                (int) ((access.msb$leftPos() + slot.x + 3.5) * client.getWindow().getScreenWidth()
                        / client.getWindow().getGuiScaledWidth()),
                (int) ((access.msb$topPos() + slot.y + 0.5) * client.getWindow().getScreenHeight()
                        / client.getWindow().getGuiScaledHeight())
            };
        });
        context.getInput().setCursorPos(0, 0); context.waitTicks(2);
        int uncovered = screenshotPixel(context.takeScreenshot(name + "-uncovered"), sample);
        if (uncovered != markerColor) throw new AssertionError("The junk marker is not visible outside the tooltip");
        move(context, 9);
        int covered = screenshotPixel(context.takeScreenshot(name), sample);
        if (covered == uncovered) throw new AssertionError("A junk marker is drawn over the native tooltip");
    }

    private static int screenshotPixel(Path path, int[] position) {
        try {
            return ImageIO.read(path.toFile()).getRGB(position[0], position[1]);
        } catch (IOException exception) { throw new AssertionError("Cannot read the rendered tooltip screenshot", exception); }
    }

    private static void verifySorting(ClientGameTestContext context, TestServerContext server) {
        var expected = box(new ItemStack(Items.STONE, 64), new ItemStack(Items.DIRT, 3));
        server.runOnServer(actual -> {
            var player = actual.getPlayerList().getPlayers().getFirst();
            player.getInventory().clearContent();
            player.getInventory().setItem(10, new ItemStack(Items.DIRT, 64));
            player.getInventory().setItem(11, new ItemStack(Items.STONE, 12));
            player.getInventory().setItem(35, expected.copy()); player.containerMenu.broadcastChanges();
        });
        context.waitFor(client -> client.player.getInventory().getItem(10).is(Items.DIRT)
                && ItemStack.matches(client.player.getInventory().getItem(35), expected));
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
            if (!inventory.getItem(9).isEmpty() || !ItemStack.matches(inventory.getItem(35), expected))
                throw new AssertionError("IPN sorting moved a designated junk slot");
        });
        context.takeScreenshot("junk-slots-ipn-sort-protected");
    }

    private static void move(ClientGameTestContext context, int index) {
        double[] position = new double[2];
        context.runOnClient(client -> {
            var screen = (AbstractContainerScreen<?>) client.screen;
            var access = (JunkSlotScreenAccess) screen;
            var slot = screen.getMenu().slots.stream().filter(s -> s.container == client.player.getInventory()
                    && s.getContainerSlot() == index).findFirst().orElseThrow();
            position[0] = (access.msb$leftPos() + slot.x + 8) * (double) client.getWindow().getScreenWidth()
                    / client.getWindow().getGuiScaledWidth();
            position[1] = (access.msb$topPos() + slot.y + 8) * (double) client.getWindow().getScreenHeight()
                    / client.getWindow().getGuiScaledHeight();
        });
        context.getInput().setCursorPos(position[0], position[1]); context.waitTicks(2);
    }
    private static ItemStack box(ItemStack... items) {
        var box = new ItemStack(Items.SHULKER_BOX); box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(items))); return box;
    }
}
