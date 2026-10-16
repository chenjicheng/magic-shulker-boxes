package dev.magicshulkerboxes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RefillAmountTest {
    @TempDir Path directory;
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @Test void acceptsOnlyWholeAmountsFromOneThroughSixtyFour() throws Exception {
        for (int amount : new int[]{1, 2, 16, 17, 63, 64}) {
            var parsed = ConfigFile.parsePreferences("{\"refillAmount\":" + amount + "}");
            assertEquals(amount, parsed.get("refillAmount").getAsInt());
        }
        for (String value : new String[]{"0", "-1", "65", "1.5", "true", "\"16\"", "null", "2147483648"})
            assertThrows(IOException.class, () -> ConfigFile.parsePreferences("{\"refillAmount\":" + value + "}"), value);
    }

    @Test void takesConfiguredAmountAndPreservesTheSourceRemainder() throws Exception {
        assertExtraction(new ItemStack(Items.STONE, 64), 1, 1);
        assertExtraction(new ItemStack(Items.STONE, 64), 17, 17);
        assertExtraction(new ItemStack(Items.STONE, 64), 64, 64);
        assertExtraction(new ItemStack(Items.STONE, 7), 32, 7);
    }

    @Test void respectsSixteenUnstackableAndComponentDefinedLimits() throws Exception {
        assertExtraction(new ItemStack(Items.ENDER_PEARL, 16), 8, 8);
        assertExtraction(new ItemStack(Items.ENDER_PEARL, 16), 17, 16);
        assertExtraction(new ItemStack(Items.IRON_PICKAXE), 64, 1);
        var custom = new ItemStack(Items.STONE, 5);
        custom.set(DataComponents.MAX_STACK_SIZE, 5);
        assertExtraction(custom, 64, 5);
    }

    @Test void ipnAndDirectEnderSourcesUseTheSameAmount() throws Exception {
        var config = ConfigFile.parseServer("{\"refillAmount\":5,\"enderChestRefill\":true}");
        var main = new SimpleContainer(41);
        main.setItem(9, box(new ItemStack(Items.STONE, 32)));
        assertEquals(5, ShulkerRefill.takeForRestock(main, 9, 0, config, 2));
        assertEquals(5, main.getItem(10).getCount());
        assertEquals(27, contents(main.getItem(9)));
        main.clearContent();
        var ender = new SimpleContainer(27);
        ender.setItem(0, new ItemStack(Items.ENDER_PEARL, 16));
        assertEquals(5, ShulkerRefill.take(new RefillSources.View(main, ender), 100, -1, Items.ENDER_PEARL, config));
        assertEquals(11, ender.getItem(0).getCount());
        assertEquals(5, main.getItem(9).getCount());
    }

    @Test void migratesBothLegacyChoicesAndPermissionsWithAnExactBackup() throws Exception {
        for (boolean full : new boolean[]{false, true}) {
            var file = directory.resolve(full + ".json");
            String original = "{\"configVersion\":2,\"refillFullStack\":" + full
                    + ",\"craftRefill\":false,\"playerEditableSettings\":[\"refillFullStack\",\"ipnRefill\"]}";
            Files.writeString(file, original);
            var migrated = ConfigFile.load(file);
            assertEquals(full ? 64 : 1, ConfigFile.options(migrated).get("refillAmount").getAsInt());
            assertFalse(migrated.craftRefill);
            assertEquals(List.of("refillAmount", "ipnRefill"), migrated.playerEditableSettings);
            assertEquals(original, Files.readString(file.resolveSibling(file.getFileName() + ".pre-0.12.0.bak")));
            assertFalse(Files.readString(file).contains("refillFullStack"));
            assertEquals(ConfigFile.json(migrated), ConfigFile.json(ConfigFile.load(file)));
        }
    }

    @Test void conflictingOrInvalidLegacyFilesAreNeverOverwritten() throws Exception {
        for (String invalid : new String[]{
                "{\"configVersion\":2,\"refillFullStack\":true,\"refillAmount\":7}",
                "{\"configVersion\":2,\"refillFullStack\":5}",
                "{\"configVersion\":2,\"refillFullStack\":true,\"refillAmount\":65}",
                "{\"configVersion\":2,\"refillFullStack\":true,\"playerEditableSettings\":[\"refillFullStack\",\"refillAmount\"]}"}) {
            var path = directory.resolve("conflicting.json");
            Files.writeString(path, invalid);
            assertThrows(IOException.class, () -> ConfigFile.load(path));
            assertEquals(invalid, Files.readString(path));
            assertFalse(Files.exists(path.resolveSibling("conflicting.json.pre-0.12.0.bak")));
        }
    }

    @Test void personalMigrationPreservesSparseChoicesAndInheritance() throws Exception {
        var file = directory.resolve("personal.json");
        Files.writeString(file, "{\"configVersion\":2,\"refillFullStack\":false,\"ipnRefill\":false}");
        var migrated = ConfigFile.readPreferences(file);
        assertEquals(1, migrated.get("refillAmount").getAsInt());
        assertFalse(migrated.get("ipnRefill").getAsBoolean());
        assertEquals(2, migrated.size());
        var draft = new dev.magicshulkerboxes.client.SettingsDraft(migrated);
        draft.amount(17);
        assertEquals(1, migrated.get("refillAmount").getAsInt(), "Draft changes cannot save themselves");
        assertEquals(17, draft.values().get("refillAmount").getAsInt());
        draft.amount(0);
        assertFalse(draft.values().has("refillAmount"), "Inheritance removes only the quantity override");
        assertFalse(draft.values().get("ipnRefill").getAsBoolean());
    }

    private static void assertExtraction(ItemStack source, int amount, int expected) throws Exception {
        var inventory = new SimpleContainer(41);
        inventory.setItem(9, box(source));
        var config = ConfigFile.parseServer("{\"refillAmount\":" + amount + "}");
        assertEquals(expected, ShulkerRefill.take(inventory, 9, 0, source.getItem(), config));
        assertEquals(expected, inventory.getItem(10).getCount());
        assertTrue(ItemStack.isSameItemSameComponents(source, inventory.getItem(10)));
        assertEquals(source.getCount() - expected, contents(inventory.getItem(9)));
    }
    private static ItemStack box(ItemStack source) {
        var box = new ItemStack(Items.SHULKER_BOX);
        box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(source)));
        return box;
    }
    private static int contents(ItemStack box) {
        return box.get(DataComponents.CONTAINER).stream().mapToInt(ItemStack::getCount).sum();
    }
}
