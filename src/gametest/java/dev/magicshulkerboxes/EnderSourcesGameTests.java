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
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.GameType;

public class EnderSourcesGameTests {
    @GameTest
    public void schematicReadsOwnDirectStorageAndRefusesDuplicateOrChangedSource(GameTestHelper h) {
        enabled(
                () -> {
                    var p = player(h);
                    var stone = new ItemStack(Items.STONE, 12);
                    p.getEnderChestInventory().setItem(3, stone.copy());
                    var request = request(p, 103, -1, stone);
                    check(
                            h,
                            RefillNetwork.accept(p, request) == 12
                                    && p.getInventory().getItem(9).getCount() == 12,
                            "Direct ender material goes to backpack");
                    check(
                            h,
                            RefillNetwork.accept(p, request) == 0
                                    && p.getEnderChestInventory().getItem(3).isEmpty(),
                            "Duplicate cannot take again");
                    var changed = player(h);
                    changed.getEnderChestInventory().setItem(3, stone.copy());
                    var stale = request(changed, 103, -1, stone);
                    changed.getEnderChestInventory()
                            .getItem(3)
                            .set(DataComponents.CUSTOM_NAME, Component.literal("Changed"));
                    check(
                            h,
                            RefillNetwork.accept(changed, stale) == 0
                                    && changed.getInventory().isEmpty(),
                            "Stale components cannot be extracted");
                    var other = player(h);
                    other.getEnderChestInventory().setItem(3, stone.copy());
                    var outsider = player(h);
                    check(
                            h,
                            RefillNetwork.accept(outsider, request(outsider, 103, -1, stone)) == 0
                                    && other.getEnderChestInventory().getItem(3).getCount() == 12,
                            "Only sender's own storage is read");
                });
        h.succeed();
    }

    @GameTest
    public void carriedBoxWinsAndEnderBoxesWorkWithFullInventory(GameTestHelper h) {
        enabled(
                () -> {
                    var p = player(h);
                    var stone = new ItemStack(Items.STONE, 8);
                    p.getInventory().setItem(9, box(stone));
                    p.getEnderChestInventory().setItem(0, stone.copy());
                    check(
                            h,
                            RefillNetwork.accept(p, request(p, 100, -1, stone)) == 8,
                            "Server applies carried-source priority");
                    check(
                            h,
                            p.getEnderChestInventory().getItem(0).getCount() == 8
                                    && stored(p.getInventory().getItem(9)) == 0,
                            "Requested ender source stays intact when carried box works");
                    var full = player(h);
                    for (int i = 0; i < 36; i++)
                        full.getInventory().setItem(i, new ItemStack(Items.DIRT, 64));
                    full.getEnderChestInventory().setItem(2, box(stone));
                    check(
                            h,
                            RefillNetwork.accept(full, request(full, 102, 0, stone)) == 8,
                            "Nested ender box can safely make room");
                    check(
                            h,
                            full.getInventory().getItem(9).getCount() == 8
                                    && stored(full.getEnderChestInventory().getItem(2)) == 64,
                            "Displaced dirt is retained in the source box");
                });
        h.succeed();
    }

    @GameTest
    public void disabledAccessAndFullDirectSourceKeepStorageUnchanged(GameTestHelper h) {
        enabled(
                () -> {
                    var p = player(h);
                    var stone = new ItemStack(Items.STONE, 8);
                    p.getEnderChestInventory().setItem(0, stone.copy());
                    MagicShulkerBoxes.config().enderChestRefill = false;
                    check(
                            h,
                            RefillNetwork.accept(p, request(p, 100, -1, stone)) == 0,
                            "Disabled source is rejected");
                    MagicShulkerBoxes.config().enderChestRefill = true;
                    var full = player(h);
                    for (int i = 0; i < 36; i++)
                        full.getInventory().setItem(i, new ItemStack(Items.DIRT, 64));
                    full.getEnderChestInventory().setItem(0, stone.copy());
                    check(
                            h,
                            RefillNetwork.accept(full, request(full, 100, -1, stone)) == 0
                                    && full.getEnderChestInventory().getItem(0).getCount() == 8,
                            "Direct source cannot overwrite full inventory");
                });
        h.succeed();
    }

    @GameTest
    public void recipeBookAndContinuousCraftingUseDirectAndNestedEnderSources(GameTestHelper h) {
        enabled(
                () -> {
                    var p = player(h);
                    p.getEnderChestInventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 4));
                    p.getEnderChestInventory().setItem(1, box(new ItemStack(Items.OAK_PLANKS, 4)));
                    var recipe =
                            h.getLevel()
                                    .getServer()
                                    .getRecipeManager()
                                    .byKey(
                                            ResourceKey.create(
                                                    Registries.RECIPE,
                                                    Identifier.withDefaultNamespace(
                                                            "crafting_table")))
                                    .orElseThrow();
                    p.inventoryMenu.handlePlacement(
                            false, false, recipe, h.getLevel(), p.getInventory());
                    check(
                            h,
                            p.inventoryMenu.getResultSlot().getItem().is(Items.CRAFTING_TABLE)
                                    && p.getEnderChestInventory().getItem(0).isEmpty(),
                            "Recipe book takes direct ender ingredients first");
                    p.inventoryMenu.clicked(0, 0, ClickType.PICKUP, p);
                    check(
                            h,
                            p.inventoryMenu.getCarried().is(Items.CRAFTING_TABLE)
                                    && p.inventoryMenu.getInputGridSlots().stream()
                                            .allMatch(s -> s.getItem().is(Items.OAK_PLANKS)),
                            "Original crafting pattern replenishes from nested ender box");
                    check(
                            h,
                            stored(p.getEnderChestInventory().getItem(1)) == 0,
                            "Exactly four more ingredients consumed");
                });
        h.succeed();
    }

    @GameTest
    public void ipnUsesDirectEnderSourceAndChecksTargetAndEligibility(GameTestHelper h) {
        enabled(
                () -> {
                    var p = player(h);
                    var food = new ItemStack(Items.GOLDEN_CARROT, 16);
                    p.getEnderChestInventory().setItem(0, food.copy());
                    var req =
                            new RestockNetwork.Request(
                                    1,
                                    100,
                                    -1,
                                    16,
                                    16,
                                    0,
                                    0,
                                    1,
                                    ItemFingerprint.of(food, p.registryAccess()),
                                    "",
                                    ItemFingerprint.of(food, p.registryAccess()));
                    check(
                            h,
                            RestockNetwork.accept(p, req) == 16
                                    && p.getInventory().getItem(9).getCount() == 16
                                    && p.getEnderChestInventory().getItem(0).isEmpty(),
                            "IPN extracts into an eligible real backpack slot");
                    check(
                            h,
                            RestockNetwork.accept(p, req) == 0,
                            "Repeated ender IPN request is refused");
                });
        h.succeed();
    }

    @SuppressWarnings("removal")
    private static ServerPlayer player(GameTestHelper h) {
        var p = h.makeMockServerPlayerInLevel();
        p.setGameMode(GameType.SURVIVAL);
        return p;
    }

    private static RefillNetwork.Request request(
            ServerPlayer p, int outer, int inner, ItemStack item) {
        return new RefillNetwork.Request(
                outer,
                inner,
                "minecraft:" + (item.is(Items.STONE) ? "stone" : "oak_planks"),
                ItemFingerprint.of(item, p.registryAccess()));
    }

    private static ItemStack box(ItemStack item) {
        var b = new ItemStack(Items.SHULKER_BOX);
        b.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(item)));
        return b;
    }

    private static int stored(ItemStack b) {
        return b.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).stream()
                .mapToInt(ItemStack::getCount)
                .sum();
    }

    private static void enabled(Runnable action) {
        var c = MagicShulkerBoxes.config();
        boolean oldEnder = c.enderChestRefill,
                oldAllow = c.allowPlayerSettings,
                oldCraft = c.craftRefill,
                oldIpn = c.ipnRefill,
                oldSchematic = c.schematicRefill;
        try {
            c.enderChestRefill = true;
            c.allowPlayerSettings = false;
            c.craftRefill = true;
            c.ipnRefill = true;
            c.schematicRefill = true;
            action.run();
        } finally {
            c.enderChestRefill = oldEnder;
            c.allowPlayerSettings = oldAllow;
            c.craftRefill = oldCraft;
            c.ipnRefill = oldIpn;
            c.schematicRefill = oldSchematic;
        }
    }

    private static void check(GameTestHelper h, boolean ok, String message) {
        h.assertTrue(ok, Component.literal(message));
    }
}
