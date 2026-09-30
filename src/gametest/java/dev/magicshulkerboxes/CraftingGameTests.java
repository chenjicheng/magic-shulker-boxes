package dev.magicshulkerboxes;

import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.GameType;

public class CraftingGameTests {
    @GameTest public void fullBackpackDoesNotNeedATemporaryMaterialSlot(GameTestHelper helper) {
        var player = player(helper, new ItemStack(Items.OAK_PLANKS, 4));
        for (int i = 0; i < 36; i++) if (i != 9) player.getInventory().setItem(i, new ItemStack(Items.DIRT, 64));
        place(helper, player, player.inventoryMenu, "crafting_table", false);
        check(helper, player.inventoryMenu.getResultSlot().getItem().is(Items.CRAFTING_TABLE), "Materials go directly into the grid");
        check(helper, stored(player) == 0, "No extra backpack slot needed");
        helper.succeed();
    }

    @GameTest public void incompatibleOldInputsAreNotDroppedToMakeRoom(GameTestHelper helper) {
        var player = player(helper, new ItemStack(Items.OAK_PLANKS, 4));
        for (int i = 0; i < 36; i++) if (i != 9) player.getInventory().setItem(i, new ItemStack(Items.DIRT, 64));
        player.getInventory().setItem(10, ItemStack.EMPTY);
        var first = new ItemStack(Items.STONE); first.set(DataComponents.CUSTOM_NAME, Component.literal("First"));
        var second = new ItemStack(Items.STONE); second.set(DataComponents.CUSTOM_NAME, Component.literal("Second"));
        player.inventoryMenu.getInputGridSlots().get(0).set(first.copy());
        player.inventoryMenu.getInputGridSlots().get(1).set(second.copy());
        place(helper, player, player.inventoryMenu, "crafting_table", false);
        check(helper, ItemStack.matches(first, player.inventoryMenu.getInputGridSlots().get(0).getItem())
                && ItemStack.matches(second, player.inventoryMenu.getInputGridSlots().get(1).getItem()), "Old component-distinct inputs stay in grid");
        check(helper, stored(player) == 4 && player.getInventory().getItem(10).isEmpty(), "No extraction or forced relocation");
        helper.succeed();
    }

    @GameTest public void failedSplitCommitRestoresInventoryAndOldGrid(GameTestHelper helper) {
        var player = player(helper, new ItemStack(Items.OAK_PLANKS, 4));
        for (int i = 0; i < 36; i++) if (i != 9) player.getInventory().setItem(i, new ItemStack(Items.DIRT, 64));
        player.getInventory().getItem(9).setCount(3); player.getInventory().setItem(10, ItemStack.EMPTY);
        player.inventoryMenu.getInputGridSlots().get(0).set(new ItemStack(Items.STONE));
        place(helper, player, player.inventoryMenu, "crafting_table", false);
        check(helper, player.getInventory().getItem(9).getCount() == 3 && stored(player) == 4, "Stacked source restored");
        check(helper, player.inventoryMenu.getInputGridSlots().get(0).getItem().is(Items.STONE)
                && player.getInventory().getItem(10).isEmpty(), "Old grid and split destination restored");
        helper.succeed();
    }

    @GameTest public void differentComponentsCannotPartiallyPopulateABatch(GameTestHelper helper) {
        var first = new ItemStack(Items.OAK_LOG, 4);
        var second = new ItemStack(Items.OAK_LOG, 4);
        var firstData = new net.minecraft.nbt.CompoundTag(); firstData.putString("kind", "first");
        var secondData = new net.minecraft.nbt.CompoundTag(); secondData.putString("kind", "second");
        first.set(DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(firstData));
        second.set(DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(secondData));
        var player = player(helper, second); player.getInventory().setItem(0, first.copy());
        place(helper, player, player.inventoryMenu, "oak_planks", true);
        check(helper, ItemStack.matches(first, player.getInventory().getItem(0)), "Inventory ingredients restored after component mismatch");
        check(helper, stored(player) == 4 && player.inventoryMenu.getInputGridSlots().stream().allMatch(slot -> slot.getItem().isEmpty()),
                "No partial placement or source extraction");
        helper.succeed();
    }

    @GameTest public void originalCakeRemaindersSurviveContinuousCrafting(GameTestHelper helper) {
        var player = player(helper, new ItemStack(Items.MILK_BUCKET), new ItemStack(Items.MILK_BUCKET), new ItemStack(Items.MILK_BUCKET),
                new ItemStack(Items.SUGAR, 2), new ItemStack(Items.WHEAT, 3), new ItemStack(Items.EGG));
        var tablePos = helper.absolutePos(new net.minecraft.core.BlockPos(1, 0, 1));
        helper.getLevel().setBlock(tablePos, net.minecraft.world.level.block.Blocks.CRAFTING_TABLE.defaultBlockState(), 3);
        player.setPos(tablePos.getX() + 0.5, tablePos.getY() + 1, tablePos.getZ() + 0.5);
        var menu = new CraftingMenu(7, player.getInventory(), net.minecraft.world.inventory.ContainerLevelAccess.create(helper.getLevel(), tablePos));
        player.containerMenu = menu;
        var inputs = List.of(Items.MILK_BUCKET, Items.MILK_BUCKET, Items.MILK_BUCKET, Items.SUGAR, Items.EGG, Items.SUGAR,
                Items.WHEAT, Items.WHEAT, Items.WHEAT);
        for (int i = 0; i < 9; i++) menu.getInputGridSlots().get(i).set(new ItemStack(inputs.get(i)));
        menu.clicked(0, 0, ClickType.PICKUP, player);
        check(helper, menu.getCarried().is(Items.CAKE) && menu.getResultSlot().getItem().is(Items.CAKE), "Cake crafted and original pattern refilled");
        int buckets = 0;
        for (int i = 0; i < 36; i++) if (player.getInventory().getItem(i).is(Items.BUCKET)) buckets += player.getInventory().getItem(i).getCount();
        check(helper, buckets == 3 && stored(player) == 0, "All three milk buckets returned without losing materials");
        helper.succeed();
    }

    @GameTest public void serverDefaultOffAndPersonalOptInStayIndependent(GameTestHelper helper) throws Exception {
        var config = MagicShulkerBoxes.config(); boolean oldCraft = config.craftRefill, oldAllow = config.allowPlayerSettings;
        try {
            config.craftRefill = false; config.allowPlayerSettings = false;
            var disabled = player(helper, new ItemStack(Items.OAK_PLANKS, 4));
            place(helper, disabled, disabled.inventoryMenu, "crafting_table", false);
            check(helper, disabled.inventoryMenu.getResultSlot().getItem().isEmpty() && stored(disabled) == 4, "Disabled feature does not import materials");
            config.allowPlayerSettings = true;
            var optedIn = player(helper, new ItemStack(Items.OAK_PLANKS, 4));
            MagicShulkerBoxes.players(helper.getLevel().getServer()).save(optedIn.getUUID(), ConfigFile.parsePreferences("{\"craftRefill\":true}"));
            place(helper, optedIn, optedIn.inventoryMenu, "crafting_table", false);
            check(helper, optedIn.inventoryMenu.getResultSlot().getItem().is(Items.CRAFTING_TABLE), "Personal opt-in overrides an off default");
        } finally { config.craftRefill = oldCraft; config.allowPlayerSettings = oldAllow; }
        helper.succeed();
    }

    @GameTest public void recipeBookFillsBackpackGridFromBox(GameTestHelper helper) {
        var player = player(helper, new ItemStack(Items.OAK_PLANKS, 8));
        place(helper, player, player.inventoryMenu, "crafting_table", false);
        check(helper, player.inventoryMenu.getResultSlot().getItem().is(Items.CRAFTING_TABLE), "2x2 recipe book sees box materials");
        check(helper, stored(player) == 4, "Exactly four planks moved to the grid");
        helper.succeed();
    }

    @GameTest public void recipeBookFillsWorkbenchAndHonorsBatchQuantity(GameTestHelper helper) {
        var player = player(helper, new ItemStack(Items.OAK_PLANKS, 24));
        var menu = new CraftingMenu(7, player.getInventory()); player.containerMenu = menu;
        place(helper, player, menu, "chest", true);
        check(helper, menu.getResultSlot().getItem().is(Items.CHEST), "3x3 recipe is filled");
        for (int i = 0; i < 9; i++) {
            check(helper, menu.getInputGridSlots().get(i).getItem().getCount() == (i == 4 ? 0 : 3), "Vanilla batch quantity and layout retained");
        }
        check(helper, stored(player) == 0, "Only required materials extracted");
        helper.succeed();
    }

    @GameTest public void takingResultRefillsTheServerObservedPattern(GameTestHelper helper) {
        var player = player(helper, new ItemStack(Items.OAK_PLANKS, 4));
        var menu = player.inventoryMenu;
        for (var slot : menu.getInputGridSlots()) slot.set(new ItemStack(Items.OAK_PLANKS));
        menu.clicked(0, 0, ClickType.PICKUP, player);
        check(helper, menu.getCarried().is(Items.CRAFTING_TABLE), "Normal click crafted one output");
        check(helper, menu.getInputGridSlots().stream().allMatch(slot -> slot.getItem().is(Items.OAK_PLANKS)), "Original pattern refilled");
        check(helper, stored(player) == 0, "Source lost exactly one recipe worth");
        helper.succeed();
    }

    @GameTest public void missingOneMaterialDoesNotPartiallyRefillAfterCraft(GameTestHelper helper) {
        var player = player(helper, new ItemStack(Items.COAL, 2)); var menu = player.inventoryMenu;
        menu.getInputGridSlots().get(0).set(new ItemStack(Items.COAL));
        menu.getInputGridSlots().get(2).set(new ItemStack(Items.STICK));
        menu.clicked(0, 0, ClickType.PICKUP, player);
        check(helper, menu.getCarried().is(Items.TORCH) && menu.getCarried().getCount() == 4, "Crafted result retained");
        check(helper, menu.getInputGridSlots().stream().allMatch(slot -> slot.getItem().isEmpty()), "No partial grid refill");
        check(helper, stored(player) == 2, "Incomplete next recipe did not extract coal");
        helper.succeed();
    }

    @GameTest public void shiftClickKeepsCraftingUntilTheExtraMaterialsRunOut(GameTestHelper helper) {
        var player = player(helper, new ItemStack(Items.OAK_PLANKS, 16)); var menu = player.inventoryMenu;
        for (var slot : menu.getInputGridSlots()) slot.set(new ItemStack(Items.OAK_PLANKS));
        menu.clicked(0, 0, ClickType.QUICK_MOVE, player);
        int count = 0;
        for (int i = 0; i < 36; i++) if (player.getInventory().getItem(i).is(Items.CRAFTING_TABLE)) count += player.getInventory().getItem(i).getCount();
        check(helper, count == 5, "Vanilla shift-click consumed the initial and replenished recipes");
        check(helper, stored(player) == 0, "Extra materials consumed exactly once");
        helper.succeed();
    }

    @SuppressWarnings("removal") private static ServerPlayer player(GameTestHelper helper, ItemStack... materials) {
        var player = helper.makeMockServerPlayerInLevel(); player.setGameMode(GameType.SURVIVAL);
        var box = new ItemStack(Items.SHULKER_BOX); box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(materials)));
        player.getInventory().setItem(9, box); return player;
    }
    private static void place(GameTestHelper helper, ServerPlayer player, AbstractCraftingMenu menu, String id, boolean all) {
        var recipe = helper.getLevel().getServer().getRecipeManager().byKey(ResourceKey.create(Registries.RECIPE,
                Identifier.withDefaultNamespace(id))).orElseThrow();
        menu.handlePlacement(all, false, recipe, helper.getLevel(), player.getInventory());
    }
    private static int stored(ServerPlayer player) {
        return player.getInventory().getItem(9).get(DataComponents.CONTAINER).stream().mapToInt(ItemStack::getCount).sum();
    }
    private static void check(GameTestHelper helper, boolean condition, String message) { helper.assertTrue(condition, Component.literal(message)); }
}
