package dev.magicshulkerboxes;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/** Exercises the vanilla recipe book and inventory-click packets over an isolated real connection. */
public class CraftingClientGameTests implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        try (var world = context.worldBuilder().create()) {
            var table = prepare(context, world, "crafting_table", 8);
            context.setScreen(() -> new InventoryScreen(net.minecraft.client.Minecraft.getInstance().player));
            context.runOnClient(client -> book(client.screen).toggleVisibility());
            context.waitFor(client -> counter(client.screen).getBiggestCraftableStack(table.value(), null) == 2);
            var tableId = display(world, table);
            context.runOnClient(client -> client.gameMode.handlePlaceRecipe(0, tableId, false));
            context.waitFor(client -> client.player.inventoryMenu.getResultSlot().getItem().is(Items.CRAFTING_TABLE));
            context.runOnClient(client -> client.gameMode.handleInventoryMouseClick(0, 0, 0, ClickType.PICKUP, client.player));
            context.waitFor(client -> client.player.inventoryMenu.getCarried().is(Items.CRAFTING_TABLE)
                    && client.player.inventoryMenu.getInputGridSlots().stream().allMatch(slot -> slot.getItem().is(Items.OAK_PLANKS))
                    && stored(client.player.getInventory().getItem(9)) == 0);
            context.waitFor(client -> counter(client.screen).getBiggestCraftableStack(table.value(), null) == 1);
            context.runOnClient(client -> client.gameMode.handleInventoryMouseClick(0, 36, 0, ClickType.PICKUP, client.player));
            context.waitFor(client -> client.player.inventoryMenu.getCarried().isEmpty());
            context.runOnClient(client -> client.gameMode.handleInventoryMouseClick(0, 0, 0, ClickType.QUICK_MOVE, client.player));
            context.waitFor(client -> client.player.inventoryMenu.getInputGridSlots().stream().allMatch(slot -> slot.getItem().isEmpty()));
            waitForServer(context, world, server -> count(server.getPlayerList().getPlayers().getFirst().getInventory(), Items.CRAFTING_TABLE) == 2);
            world.getServer().runOnServer(server -> {
                var inventory = server.getPlayerList().getPlayers().getFirst().getInventory(); int tables = 0;
                for (int i = 0; i < 36; i++) if (inventory.getItem(i).is(Items.CRAFTING_TABLE)) tables += inventory.getItem(i).getCount();
                check(tables == 2, "Two tables crafted through ordinary client/server clicks");
            });
            var chest = prepare(context, world, "chest", 24);
            world.getServer().runOnServer(server -> {
                var player = server.getPlayerList().getPlayers().getFirst(); var level = player.level();
                var pos = player.blockPosition().offset(0, 0, 2); level.setBlock(pos, Blocks.CRAFTING_TABLE.defaultBlockState(), 3);
                player.openMenu(new SimpleMenuProvider((id, inventory, owner) -> new CraftingMenu(id, inventory,
                        ContainerLevelAccess.create(level, pos)), Component.literal("Crafting test")));
            });
            context.waitFor(client -> client.player.containerMenu instanceof CraftingMenu);
            var chestId = display(world, chest);
            context.runOnClient(client -> client.gameMode.handlePlaceRecipe(client.player.containerMenu.containerId, chestId, true));
            context.waitFor(client -> ((CraftingMenu) client.player.containerMenu).getResultSlot().getItem().is(Items.CHEST));
            context.runOnClient(client -> {
                var menu = (CraftingMenu) client.player.containerMenu;
                for (int i = 0; i < 9; i++) check(menu.getInputGridSlots().get(i).getItem().getCount() == (i == 4 ? 0 : 3), "Native 3x3 batch layout");
                client.gameMode.handleInventoryMouseClick(menu.containerId, 0, 0, ClickType.QUICK_MOVE, client.player);
            });
            context.waitFor(client -> ((CraftingMenu) client.player.containerMenu).getInputGridSlots().stream().allMatch(slot -> slot.getItem().isEmpty()));
            waitForServer(context, world, server -> count(server.getPlayerList().getPlayers().getFirst().getInventory(), Items.CHEST) == 3);
            world.getServer().runOnServer(server -> {
                var inventory = server.getPlayerList().getPlayers().getFirst().getInventory(); int chests = 0;
                for (int i = 0; i < 36; i++) if (inventory.getItem(i).is(Items.CHEST)) chests += inventory.getItem(i).getCount();
                check(chests == 3 && stored(inventory.getItem(9)) == 0, "Three chests crafted once with exact source consumption");
            });
            enderRecipeFlow(context, world, table, tableId);
            context.takeScreenshot("crafting-refill-verified");
        }
    }

    private static void enderRecipeFlow(ClientGameTestContext context, TestSingleplayerContext world,
                                       RecipeHolder<?> recipe, RecipeDisplayId id) {
        world.getServer().runOnServer(server -> {
            var p = server.getPlayerList().getPlayers().getFirst(); p.closeContainer();
            p.getInventory().clearContent(); p.getEnderChestInventory().clearContent();
            p.getEnderChestInventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 4));
            var box = new ItemStack(Items.SHULKER_BOX);
            box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.OAK_PLANKS, 4))));
            p.getEnderChestInventory().setItem(1, box);
            MagicShulkerBoxes.config().enderChestRefill = true; SettingsNetwork.broadcastPolicy(server);
        });
        context.waitFor(client -> client.player.containerMenu == client.player.inventoryMenu
                && client.player.getInventory().isEmpty() && client.player.getEnderChestInventory().getItem(0).getCount() == 4
                && stored(client.player.getEnderChestInventory().getItem(1)) == 4);
        context.setScreen(() -> new InventoryScreen(net.minecraft.client.Minecraft.getInstance().player));
        context.runOnClient(client -> {
            if (!book(client.screen).isVisible()) book(client.screen).toggleVisibility();
        });
        context.waitFor(client -> counter(client.screen).getBiggestCraftableStack(recipe.value(), null) == 2);
        context.runOnClient(client -> client.gameMode.handlePlaceRecipe(0, id, false));
        context.waitFor(client -> client.player.inventoryMenu.getResultSlot().getItem().is(Items.CRAFTING_TABLE));
        context.runOnClient(client -> client.gameMode.handleInventoryMouseClick(0, 0, 0, ClickType.PICKUP, client.player));
        context.waitFor(client -> client.player.inventoryMenu.getCarried().is(Items.CRAFTING_TABLE)
                && client.player.inventoryMenu.getInputGridSlots().stream().allMatch(slot -> slot.getItem().is(Items.OAK_PLANKS))
                && stored(client.player.getEnderChestInventory().getItem(1)) == 0);
        world.getServer().runOnServer(server -> {
            var p = server.getPlayerList().getPlayers().getFirst();
            check(p.getEnderChestInventory().getItem(0).isEmpty() && stored(p.getEnderChestInventory().getItem(1)) == 0,
                    "Real recipe placement and continuous refill used exactly eight ender planks");
            check(p.inventoryMenu.getCarried().getCount() == 1, "Server confirms exactly one crafted table");
        });
    }

    private static RecipeHolder<?> prepare(ClientGameTestContext context, TestSingleplayerContext world, String name, int amount) {
        var recipe = world.getServer().computeOnServer(server -> {
            MagicShulkerBoxes.config().craftRefill = true; MagicShulkerBoxes.config().allowPlayerSettings = false;
            var player = server.getPlayerList().getPlayers().getFirst(); player.setGameMode(GameType.SURVIVAL);
            player.getInventory().clearContent();
            var box = new ItemStack(Items.SHULKER_BOX); box.set(DataComponents.CONTAINER,
                    ItemContainerContents.fromItems(List.of(new ItemStack(Items.OAK_PLANKS, amount))));
            player.getInventory().setItem(9, box);
            var holder = server.getRecipeManager().byKey(ResourceKey.create(Registries.RECIPE, Identifier.withDefaultNamespace(name))).orElseThrow();
            player.awardRecipes(List.of(holder)); player.containerMenu.broadcastChanges(); return holder;
        });
        context.waitFor(client -> !client.player.isCreative() && stored(client.player.getInventory().getItem(9)) == amount);
        return recipe;
    }
    private static RecipeDisplayId display(TestSingleplayerContext world, RecipeHolder<?> recipe) {
        return world.getServer().computeOnServer(server -> {
            var entries = new ArrayList<net.minecraft.world.item.crafting.display.RecipeDisplayEntry>();
            server.getRecipeManager().listDisplaysForRecipe(recipe.id(), entries::add); return entries.getFirst().id();
        });
    }
    private static int count(net.minecraft.world.Container inventory, net.minecraft.world.item.Item item) {
        int total = 0;
        for (int i = 0; i < 36; i++) if (inventory.getItem(i).is(item)) total += inventory.getItem(i).getCount();
        return total;
    }
    private static void waitForServer(ClientGameTestContext context, TestSingleplayerContext world,
                                      java.util.function.Predicate<net.minecraft.server.MinecraftServer> complete) {
        for (int i = 0; i < 200; i++) {
            if (world.getServer().computeOnServer(complete::test)) return;
            context.waitTick();
        }
        throw new AssertionError("Server did not confirm the predicted crafting clicks");
    }
    private static RecipeBookComponent<?> book(Object screen) {
        return (RecipeBookComponent<?>) field(AbstractRecipeBookScreen.class, screen, "recipeBookComponent");
    }
    private static StackedItemContents counter(Object screen) { return (StackedItemContents) field(RecipeBookComponent.class, book(screen), "stackedContents"); }
    private static Object field(Class<?> owner, Object object, String name) {
        try { Field field = owner.getDeclaredField(name); field.setAccessible(true); return field.get(object); }
        catch (ReflectiveOperationException exception) { throw new AssertionError("Vanilla recipe-book field changed", exception); }
    }
    private static int stored(ItemStack box) { return box.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).stream().mapToInt(ItemStack::getCount).sum(); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
