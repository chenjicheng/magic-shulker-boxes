package dev.magicshulkerboxes;

import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EnderSourcesTest {
    @BeforeAll
    static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void enderConfigIsAnIndependentOptIn() throws Exception {
        assertFalse(ConfigFile.options(new StorageConfig()).get("enderChestRefill").getAsBoolean());
        assertTrue(
                ConfigFile.options(
                                ConfigFile.apply(
                                        new StorageConfig(),
                                        ConfigFile.parsePreferences("{\"enderChestRefill\":true}")))
                        .get("enderChestRefill")
                        .getAsBoolean());
    }

    @Test
    void directItemsTransferAtomicallyAndDisabledAccessCannotExtract() {
        var main = new SimpleContainer(41);
        var ender = new SimpleContainer(27);
        var sources = new RefillSources.View(main, ender);
        var c = new StorageConfig();
        ender.setItem(4, new ItemStack(Items.STONE, 12));
        assertEquals(0, ShulkerRefill.take(sources, 104, -1, Items.STONE, c));
        c.enderChestRefill = true;
        assertEquals(12, ShulkerRefill.take(sources, 104, -1, Items.STONE, c));
        assertEquals(12, main.getItem(9).getCount());
        assertTrue(ender.getItem(4).isEmpty());
        for (int i = 0; i < 36; i++) main.setItem(i, new ItemStack(Items.DIRT, 64));
        ender.setItem(4, new ItemStack(Items.STONE, 12));
        assertEquals(0, ShulkerRefill.take(sources, 104, -1, Items.STONE, c));
        assertEquals(12, ender.getItem(4).getCount());
    }

    @Test
    void failedMultiIngredientCraftDoesNotConsumeAnyEnderSource() {
        var main = new SimpleContainer(41);
        var ender = new SimpleContainer(27);
        var sources = new RefillSources.View(main, ender);
        var config = new StorageConfig();
        config.enderChestRefill = true;
        ender.setItem(0, new ItemStack(Items.STONE, 8));
        var grid = new SimpleContainer(2);
        assertFalse(
                CraftingRefill.refill(
                        sources,
                        grid,
                        List.of(new ItemStack(Items.STONE), new ItemStack(Items.DIAMOND)),
                        List.of(ItemStack.EMPTY, ItemStack.EMPTY),
                        config));
        assertEquals(8, ender.getItem(0).getCount());
        assertTrue(grid.isEmpty());
        assertTrue(main.isEmpty());
    }
}
