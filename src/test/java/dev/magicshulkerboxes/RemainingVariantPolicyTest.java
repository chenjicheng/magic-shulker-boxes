package dev.magicshulkerboxes;

import java.util.List;
import java.util.Optional;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.LodestoneTracker;
import net.minecraft.world.item.component.WritableBookContent;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BannerPattern;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static dev.magicshulkerboxes.ShulkerStorageTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Audit the remaining payloads without deciding that every decorative or entity field defines a type. */
class RemainingVariantPolicyTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @Test void unselectedPayloadsFollowTheStrictSwitchAndAreNeverMergedOrLost() {
        var fish = new ItemStack(Items.TROPICAL_FISH_BUCKET); var tag = new CompoundTag(); tag.putInt("BucketVariantTag", 0);
        fish.set(DataComponents.BUCKET_ENTITY_DATA, CustomData.of(tag));
        var otherFish = fish.copy(); tag = new CompoundTag(); tag.putInt("BucketVariantTag", 65536);
        otherFish.set(DataComponents.BUCKET_ENTITY_DATA, CustomData.of(tag));
        assertOptionalGrouping(fish, otherFish);

        var book = new ItemStack(Items.WRITTEN_BOOK);
        book.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(Filterable.passThrough("Book"), "Author", 0,
                List.of(Filterable.passThrough(Component.literal("First page"))), true));
        var otherBook = book.copy();
        otherBook.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(Filterable.passThrough("Book"), "Author", 0,
                List.of(Filterable.passThrough(Component.literal("Other page"))), true));
        assertOptionalGrouping(book, otherBook);
        var writable = new ItemStack(Items.WRITABLE_BOOK);
        writable.set(DataComponents.WRITABLE_BOOK_CONTENT, new WritableBookContent(List.of(Filterable.passThrough("First"))));
        var otherWritable = writable.copy();
        otherWritable.set(DataComponents.WRITABLE_BOOK_CONTENT, new WritableBookContent(List.of(Filterable.passThrough("Other"))));
        assertOptionalGrouping(writable, otherWritable);

        var compass = new ItemStack(Items.COMPASS);
        compass.set(DataComponents.LODESTONE_TRACKER, new LodestoneTracker(Optional.of(GlobalPos.of(Level.OVERWORLD, new BlockPos(0, 64, 0))), false));
        var otherCompass = compass.copy();
        otherCompass.set(DataComponents.LODESTONE_TRACKER, new LodestoneTracker(Optional.of(GlobalPos.of(Level.OVERWORLD, new BlockPos(100, 64, 0))), false));
        assertOptionalGrouping(compass, otherCompass);

        var pattern = Holder.direct(new BannerPattern(Identifier.withDefaultNamespace("cross"), "block.minecraft.banner.cross"));
        var banner = new ItemStack(Items.WHITE_BANNER);
        banner.set(DataComponents.BANNER_PATTERNS, new BannerPatternLayers.Builder().add(pattern, DyeColor.RED).build());
        var otherBanner = banner.copy();
        otherBanner.set(DataComponents.BANNER_PATTERNS, new BannerPatternLayers.Builder().add(pattern, DyeColor.BLUE).build());
        assertOptionalGrouping(banner, otherBanner);
    }

    private static void assertOptionalGrouping(ItemStack first, ItemStack second) {
        var inventory = fullInventory(); inventory.setItem(0, box(first.copy()));
        var config = new StorageConfig(); config.pickupStorageEnabled = true; config.makeSpaceMode = StorageConfig.MakeSpaceMode.DISABLED;
        assertEquals(1, ShulkerStorage.store(inventory, second.copy(), config));
        var items = inventory.getItem(0).get(DataComponents.CONTAINER).stream().filter(stack -> !stack.isEmpty()).toList();
        assertEquals(2, items.size(), "Payload differences are preserved as separate stacks inside the box");
        assertTrue(ItemStack.matches(items.get(0), first)); assertTrue(ItemStack.matches(items.get(1), second));
        inventory.setItem(0, box(first.copy())); config.matchItemComponents = true;
        assertEquals(0, ShulkerStorage.store(inventory, second.copy(), config));
        assertEquals(1, countContents(inventory.getItem(0)));
    }
}
