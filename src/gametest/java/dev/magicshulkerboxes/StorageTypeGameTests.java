package dev.magicshulkerboxes;

import java.lang.reflect.Method;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Instruments;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.SuspiciousStewEffects;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.world.item.component.FireworkExplosion;
import net.minecraft.world.item.component.InstrumentComponent;
import net.minecraft.world.item.component.OminousBottleAmplifier;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.saveddata.maps.MapId;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.world.level.GameType;

public class StorageTypeGameTests implements CustomTestMethodInvoker {
    @Override public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
        var config = MagicShulkerBoxes.config();
        boolean pickup = config.pickupStorageEnabled, strict = config.matchItemComponents;
        boolean personal = config.allowPlayerSettings, emptyFirst = config.preferEmptyBoxesOverInventory;
        var space = config.makeSpaceMode;
        try {
            config.pickupStorageEnabled = true; config.matchItemComponents = false;
            config.allowPlayerSettings = false; config.preferEmptyBoxesOverInventory = false;
            config.makeSpaceMode = StorageConfig.MakeSpaceMode.DISABLED;
            method.invoke(this, helper);
        } finally {
            config.pickupStorageEnabled = pickup; config.matchItemComponents = strict;
            config.allowPlayerSettings = personal; config.preferEmptyBoxesOverInventory = emptyFirst;
            config.makeSpaceMode = space;
        }
    }

    @GameTest public void differentPotionUsesVanillaInventoryInsteadOfWrongMatchingBox(GameTestHelper helper) {
        var player = player(helper, false);
        var strength = potion(Potions.STRENGTH); var speed = potion(Potions.SWIFTNESS);
        player.getInventory().setItem(9, box(strength.copy()));
        var dropped = drop(helper, speed.copy()); dropped.playerTouch(player);
        check(helper, storedCount(player.getInventory().getItem(9)) == 1, "Speed must not enter the strength box");
        check(helper, ItemStack.matches(player.getInventory().getItem(0), speed), "Vanilla receives the different potion in a free slot");
        check(helper, dropped.isRemoved(), "Exactly one potion was collected");
        helper.succeed();
    }

    @GameTest public void fullInventoryUsesEmptyBoxWithoutMixingPotionEffects(GameTestHelper helper) {
        var player = player(helper, true);
        var strength = potion(Potions.STRENGTH); var speed = potion(Potions.SWIFTNESS);
        player.getInventory().setItem(9, box(strength.copy())); player.getInventory().setItem(10, box());
        var dropped = drop(helper, speed.copy()); dropped.playerTouch(player);
        check(helper, storedCount(player.getInventory().getItem(9)) == 1, "Existing strength box remains single-type");
        check(helper, ItemStack.matches(stored(player.getInventory().getItem(10)).getFirst(), speed), "Speed enters the separate empty box");
        check(helper, dropped.isRemoved(), "Pickup preserves the total potion count");
        helper.succeed();
    }

    @GameTest public void noCompatibleBoxLeavesPotionOnGround(GameTestHelper helper) {
        var player = player(helper, true);
        var speed = potion(Potions.SWIFTNESS);
        player.getInventory().setItem(9, box(potion(Potions.STRENGTH)));
        var dropped = drop(helper, speed.copy()); dropped.playerTouch(player);
        check(helper, !dropped.isRemoved() && ItemStack.matches(dropped.getItem(), speed), "Unaccepted potion stays on ground unchanged");
        check(helper, storedCount(player.getInventory().getItem(9)) == 1, "A mismatched box is not a fallback destination");
        helper.succeed();
    }

    @GameTest public void realChestShiftTransferSeparatesPotionTypes(GameTestHelper helper) {
        var player = player(helper, true);
        var speed = potion(Potions.SWIFTNESS);
        player.getInventory().setItem(9, box(potion(Potions.STRENGTH))); player.getInventory().setItem(10, box());
        var chest = TestContainers.chest(helper, player); chest.setItem(0, speed.copy());
        var menu = new ChestMenu(MenuType.GENERIC_9x3, 1, player.getInventory(), chest, 3); player.containerMenu = menu;
        menu.clicked(0, 0, ClickType.QUICK_MOVE, player);
        check(helper, ItemStack.matches(chest.getItem(0), speed) && storedCount(player.getInventory().getItem(10)) == 0,
                "A vanilla-rejected transfer is not synthesized into a box");
        player.getInventory().setItem(11, ItemStack.EMPTY);
        MagicShulkerBoxes.config().preferEmptyBoxesOverInventory = true;
        menu.clicked(0, 0, ClickType.QUICK_MOVE, player);
        check(helper, chest.getItem(0).isEmpty(), "Actual chest Shift-click takes one potion");
        check(helper, storedCount(player.getInventory().getItem(9)) == 1, "Container transfer does not use the wrong potion box");
        check(helper, ItemStack.matches(stored(player.getInventory().getItem(10)).getFirst(), speed), "Container transfer uses the separate box");
        helper.succeed();
    }

    @GameTest public void suspiciousStewEffectsAndDurationAreAlwaysDifferentTypes(GameTestHelper helper) {
        var first = new ItemStack(Items.SUSPICIOUS_STEW);
        first.set(DataComponents.SUSPICIOUS_STEW_EFFECTS, new SuspiciousStewEffects(List.of(new SuspiciousStewEffects.Entry(MobEffects.POISON, 100))));
        var otherEffect = first.copy();
        otherEffect.set(DataComponents.SUSPICIOUS_STEW_EFFECTS, new SuspiciousStewEffects(List.of(new SuspiciousStewEffects.Entry(MobEffects.BLINDNESS, 100))));
        assertSeparatePickup(helper, first, otherEffect);
        var longer = first.copy();
        longer.set(DataComponents.SUSPICIOUS_STEW_EFFECTS, new SuspiciousStewEffects(List.of(new SuspiciousStewEffects.Entry(MobEffects.POISON, 200))));
        assertSeparatePickup(helper, first, longer);
        helper.succeed();
    }

    @GameTest public void enchantedBooksAlwaysDistinguishStoredEnchantmentsAndLevels(GameTestHelper helper) {
        var enchantments = helper.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        var first = new ItemStack(Items.ENCHANTED_BOOK);
        var contents = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
        contents.set(enchantments.getOrThrow(Enchantments.PROTECTION), 1);
        first.set(DataComponents.STORED_ENCHANTMENTS, contents.toImmutable());
        var stronger = first.copy(); contents = new ItemEnchantments.Mutable(first.get(DataComponents.STORED_ENCHANTMENTS));
        contents.set(enchantments.getOrThrow(Enchantments.PROTECTION), 2);
        stronger.set(DataComponents.STORED_ENCHANTMENTS, contents.toImmutable());
        assertSeparatePickup(helper, first, stronger);
        var other = new ItemStack(Items.ENCHANTED_BOOK); contents = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
        contents.set(enchantments.getOrThrow(Enchantments.SHARPNESS), 1);
        other.set(DataComponents.STORED_ENCHANTMENTS, contents.toImmutable());
        assertSeparatePickup(helper, first, other);
        helper.succeed();
    }

    @GameTest public void filledMapIdsAreAlwaysDifferentTypes(GameTestHelper helper) {
        var first = new ItemStack(Items.FILLED_MAP); first.set(DataComponents.MAP_ID, new MapId(1));
        var second = first.copy(); second.set(DataComponents.MAP_ID, new MapId(2));
        assertSeparatePickup(helper, first, second);
        helper.succeed();
    }

    @GameTest public void fireworksAlwaysDistinguishFlightAndExplosionContents(GameTestHelper helper) {
        var first = new ItemStack(Items.FIREWORK_ROCKET); first.set(DataComponents.FIREWORKS, new Fireworks(1, List.of()));
        var longer = first.copy(); longer.set(DataComponents.FIREWORKS, new Fireworks(3, List.of()));
        assertSeparatePickup(helper, first, longer);
        var red = new FireworkExplosion(FireworkExplosion.Shape.SMALL_BALL, IntList.of(0xFF0000), IntList.of(), false, false);
        var blue = new FireworkExplosion(FireworkExplosion.Shape.SMALL_BALL, IntList.of(0x0000FF), IntList.of(), false, false);
        var second = first.copy(); second.set(DataComponents.FIREWORKS, new Fireworks(1, List.of(red)));
        assertSeparatePickup(helper, first, second);
        var star = new ItemStack(Items.FIREWORK_STAR); star.set(DataComponents.FIREWORK_EXPLOSION, red);
        var otherStar = star.copy(); otherStar.set(DataComponents.FIREWORK_EXPLOSION, blue);
        assertSeparatePickup(helper, star, otherStar);
        helper.succeed();
    }

    @GameTest public void goatHornsAlwaysDistinguishTheirSound(GameTestHelper helper) {
        var first = new ItemStack(Items.GOAT_HORN); first.set(DataComponents.INSTRUMENT, new InstrumentComponent(Instruments.PONDER_GOAT_HORN));
        var second = first.copy(); second.set(DataComponents.INSTRUMENT, new InstrumentComponent(Instruments.SING_GOAT_HORN));
        assertSeparatePickup(helper, first, second);
        helper.succeed();
    }

    @GameTest public void ominousBottlesAlwaysDistinguishTheirLevel(GameTestHelper helper) {
        var first = new ItemStack(Items.OMINOUS_BOTTLE); first.set(DataComponents.OMINOUS_BOTTLE_AMPLIFIER, new OminousBottleAmplifier(0));
        var second = first.copy(); second.set(DataComponents.OMINOUS_BOTTLE_AMPLIFIER, new OminousBottleAmplifier(1));
        assertSeparatePickup(helper, first, second);
        helper.succeed();
    }

    @GameTest public void ordinaryToolEnchantmentsRemainControlledByTheExistingSwitch(GameTestHelper helper) {
        var enchantments = helper.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        var first = new ItemStack(Items.DIAMOND_PICKAXE); var contents = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
        contents.set(enchantments.getOrThrow(Enchantments.EFFICIENCY), 1); first.set(DataComponents.ENCHANTMENTS, contents.toImmutable());
        var second = new ItemStack(Items.DIAMOND_PICKAXE); contents = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
        contents.set(enchantments.getOrThrow(Enchantments.UNBREAKING), 1); second.set(DataComponents.ENCHANTMENTS, contents.toImmutable());
        var loose = player(helper, true); loose.getInventory().setItem(9, box(first.copy()));
        var dropped = drop(helper, second.copy()); dropped.playerTouch(loose);
        check(helper, dropped.isRemoved() && storedCount(loose.getInventory().getItem(9)) == 2,
                "Ordinary tool enchantments still follow relaxed component matching");
        MagicShulkerBoxes.config().matchItemComponents = true;
        assertSeparatePickup(helper, first, second);
        helper.succeed();
    }

    private static void assertSeparatePickup(GameTestHelper helper, ItemStack first, ItemStack second) {
        var player = player(helper, true); var original = box(first.copy()); player.getInventory().setItem(9, original.copy());
        var dropped = drop(helper, second.copy()); dropped.playerTouch(player);
        check(helper, !dropped.isRemoved() && ItemStack.matches(dropped.getItem(), second),
                "A different intrinsic variant stays on the ground: " + second);
        check(helper, ItemStack.matches(player.getInventory().getItem(9), original), "The existing type and payload remain untouched");
    }

    @SuppressWarnings("removal") private static ServerPlayer player(GameTestHelper helper, boolean full) {
        var player = helper.makeMockServerPlayerInLevel(); player.setGameMode(GameType.SURVIVAL);
        if (full) for (int slot = 0; slot < 36; slot++) player.getInventory().setItem(slot, new ItemStack(Items.STONE, 64));
        return player;
    }
    private static ItemEntity drop(GameTestHelper helper, ItemStack stack) {
        var dropped = new ItemEntity(helper.getLevel(), 0, 4, 0, stack); dropped.setNoPickUpDelay(); return dropped;
    }
    private static ItemStack potion(net.minecraft.core.Holder<net.minecraft.world.item.alchemy.Potion> type) {
        return PotionContents.createItemStack(Items.POTION, type);
    }
    private static ItemStack box(ItemStack... items) {
        var box = new ItemStack(Items.SHULKER_BOX); box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(items))); return box;
    }
    private static List<ItemStack> stored(ItemStack box) {
        return box.get(DataComponents.CONTAINER).stream().filter(stack -> !stack.isEmpty()).toList();
    }
    private static int storedCount(ItemStack box) { return stored(box).stream().mapToInt(ItemStack::getCount).sum(); }
    private static void check(GameTestHelper helper, boolean value, String reason) { helper.assertTrue(value, Component.literal(reason)); }
}
