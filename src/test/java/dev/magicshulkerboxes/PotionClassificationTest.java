package dev.magicshulkerboxes;

import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static dev.magicshulkerboxes.ShulkerStorageTest.*;
import static org.junit.jupiter.api.Assertions.*;

class PotionClassificationTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @Test void differentEffectsNeverShareAPotionOrTippedArrowBox() {
        for (var item : List.of(Items.POTION, Items.SPLASH_POTION, Items.LINGERING_POTION, Items.TIPPED_ARROW)) {
            assertSeparate(potion(item, Potions.STRENGTH), potion(item, Potions.SWIFTNESS));
        }
    }

    @Test void extendedAndEnhancedVersionsRemainDifferentTypes() {
        assertSeparate(potion(Items.POTION, Potions.SWIFTNESS), potion(Items.POTION, Potions.LONG_SWIFTNESS));
        assertSeparate(potion(Items.POTION, Potions.SWIFTNESS), potion(Items.POTION, Potions.STRONG_SWIFTNESS));
        assertSeparate(potion(Items.POTION, Potions.LONG_SWIFTNESS), potion(Items.POTION, Potions.STRONG_SWIFTNESS));
        assertSeparate(potion(Items.POTION, Potions.WATER), potion(Items.POTION, Potions.AWKWARD));
    }

    @Test void customEffectsAndDurationScaleRemainDifferentTypes() {
        var poison = potion(Items.POTION, Potions.WATER);
        poison.set(DataComponents.POTION_CONTENTS, PotionContents.EMPTY.withEffectAdded(new MobEffectInstance(MobEffects.POISON, 200, 0)));
        var stronger = poison.copy();
        stronger.set(DataComponents.POTION_CONTENTS, PotionContents.EMPTY.withEffectAdded(new MobEffectInstance(MobEffects.POISON, 200, 1)));
        assertSeparate(poison, stronger);
        var longer = poison.copy();
        longer.set(DataComponents.POTION_CONTENTS, PotionContents.EMPTY.withEffectAdded(new MobEffectInstance(MobEffects.POISON, 400, 0)));
        assertSeparate(poison, longer);
        var scaled = poison.copy();
        scaled.set(DataComponents.POTION_DURATION_SCALE, 0.5F);
        assertSeparate(poison, scaled);
    }

    @Test void samePotionStillSharesABoxAndNamesFollowTheExistingSwitch() {
        var plain = potion(Items.POTION, Potions.STRENGTH);
        var named = plain.copy(); named.set(DataComponents.CUSTOM_NAME, Component.literal("Named strength"));
        var inventory = fullInventory(); inventory.setItem(0, box(plain));
        assertEquals(1, ShulkerStorage.store(inventory, plain.copy(), config()));
        assertEquals(2, countContents(inventory.getItem(0)));
        assertEquals(1, ShulkerStorage.store(inventory, named.copy(), config()));
        var strict = config(); strict.matchItemComponents = true;
        assertEquals(0, ShulkerStorage.store(inventory, potion(Items.POTION, Potions.STRENGTH), strict),
                "A box mixing named and unnamed variants is not a strict single-type box");
    }

    @Test void existingMixedPotionBoxesAreSkippedAndEmptyBoxesRemainAvailable() {
        var strength = potion(Items.POTION, Potions.STRENGTH);
        var speed = potion(Items.POTION, Potions.SWIFTNESS);
        var mixed = box(strength, speed);
        var inventory = fullInventory(); inventory.setItem(0, mixed.copy());
        assertEquals(0, ShulkerStorage.store(inventory, speed.copy(), config()));
        assertTrue(ItemStack.matches(mixed, inventory.getItem(0)));
        inventory.setItem(1, box());
        assertEquals(1, ShulkerStorage.store(inventory, speed.copy(), config()));
        assertTrue(ItemStack.matches(mixed, inventory.getItem(0)));
        assertTrue(ItemStack.isSameItemSameComponents(speed, stored(inventory.getItem(1)).getFirst()));
    }

    @Test void pickupSpaceMakingDoesNotMixDisplacedPotionsWithIncomingPotions() {
        var inventory = fullInventory(); inventory.setItem(0, box()); inventory.getItem(0).setCount(16);
        var strength = potion(Items.POTION, Potions.STRENGTH);
        var speed = potion(Items.POTION, Potions.SWIFTNESS);
        inventory.setItem(9, strength.copy());
        var config = config(); config.makeSpaceMode = StorageConfig.MakeSpaceMode.MOVE_TO_BOX;
        assertEquals(1, ShulkerStorage.store(inventory, speed.copy(), config));
        for (int slot = 0; slot < 36; slot++) {
            var stack = inventory.getItem(slot);
            if (!ShulkerStorage.isShulker(stack)) continue;
            var items = stored(stack);
            if (items.isEmpty()) continue;
            assertTrue(items.stream().allMatch(item -> ItemStack.isSameItemSameComponents(item, items.getFirst())),
                    "Space-making must preserve each distinct potion in its own box");
        }
    }

    @Test void refillSpaceMakingCannotPutStrengthIntoARemainingSpeedSource() {
        var inventory = fullInventory();
        var speed = potion(Items.POTION, Potions.SWIFTNESS);
        var strength = potion(Items.POTION, Potions.STRENGTH);
        var source = box(speed, speed.copy());
        inventory.setItem(35, source.copy()); inventory.setItem(9, strength.copy());
        assertEquals(0, ShulkerRefill.take(inventory, 35, 0, Items.POTION, new StorageConfig()));
        assertTrue(ItemStack.matches(source, inventory.getItem(35)));
        assertTrue(ItemStack.matches(strength, inventory.getItem(9)));
        inventory.setItem(34, box());
        assertEquals(1, ShulkerRefill.take(inventory, 35, 0, Items.POTION, new StorageConfig()));
        assertTrue(ItemStack.isSameItemSameComponents(strength, stored(inventory.getItem(34)).getFirst()));
        assertTrue(stored(inventory.getItem(35)).stream().allMatch(item -> ItemStack.isSameItemSameComponents(item, speed)));
    }

    @Test void reservedPickupRejectsABoxWithDifferentPotionContents() {
        var inventory = new SimpleContainer(41);
        var speed = potion(Items.POTION, Potions.SWIFTNESS);
        var original = box(potion(Items.POTION, Potions.STRENGTH)); inventory.setItem(9, original);
        assertEquals(0, ShulkerStorage.collectRelocated(inventory, 9, original, speed, config()));
        assertEquals(1, speed.getCount());
        assertEquals(1, countContents(original));
    }

    @Test void reservedPickupAlsoHonorsStrictMatchingIfAnotherModChangesBoxContents() {
        var inventory = fullInventory(); inventory.setItem(0, box()); inventory.getItem(0).setCount(16);
        var named = new ItemStack(Items.STONE, 3); named.set(DataComponents.CUSTOM_NAME, Component.literal("Reserved"));
        inventory.setItem(9, named.copy());
        var config = config(); config.matchItemComponents = true; config.makeSpaceMode = StorageConfig.MakeSpaceMode.DROP_AND_PICKUP;
        assertEquals(1, ShulkerStorage.store(inventory, new ItemStack(Items.COBBLESTONE), config, true, (transfers, commit) -> {
            commit.run();
            var transfer = transfers.getFirst(); var expected = inventory.getItem(transfer.destination());
            var different = new ItemStack(Items.STONE); different.set(DataComponents.CUSTOM_NAME, Component.literal("Other"));
            // Keep the reserved box identity while simulating another pickup hook editing its contents.
            expected.set(DataComponents.CONTAINER, net.minecraft.world.item.component.ItemContainerContents.fromItems(List.of(different)));
            assertEquals(0, ShulkerStorage.collectRelocated(inventory, transfer.destination(), expected, transfer.stack(), config));
            assertEquals(3, transfer.stack().getCount());
            assertEquals(1, countContents(expected));
        }));
    }

    private static void assertSeparate(ItemStack first, ItemStack second) {
        var inventory = fullInventory(); var original = box(first.copy()); inventory.setItem(0, original.copy());
        var incoming = second.copy();
        assertEquals(0, ShulkerStorage.store(inventory, incoming, config()), "Different potion payload must not enter the existing box");
        assertTrue(ItemStack.matches(second, incoming));
        assertTrue(ItemStack.matches(original, inventory.getItem(0)));
    }
    private static StorageConfig config() {
        var config = new StorageConfig(); config.pickupStorageEnabled = true;
        config.makeSpaceMode = StorageConfig.MakeSpaceMode.DISABLED; return config;
    }
    static ItemStack potion(Item item, Holder<Potion> type) { return PotionContents.createItemStack(item, type); }
    private static List<ItemStack> stored(ItemStack box) {
        return box.get(DataComponents.CONTAINER).stream().filter(stack -> !stack.isEmpty()).toList();
    }
}
