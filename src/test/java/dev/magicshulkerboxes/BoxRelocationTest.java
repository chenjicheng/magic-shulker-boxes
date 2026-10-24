package dev.magicshulkerboxes;

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
import static org.junit.jupiter.api.Assertions.*;

class BoxRelocationTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @Test void emptyBoxDestinationObeysUseEmptyBoxes() {
        var inventory = new SimpleContainer(41);
        inventory.setItem(9, box());
        var config = new StorageConfig();
        config.useEmptyBoxes = false;
        assertEquals(-1, BoxRelocation.store(inventory, new ItemStack(Items.STONE, 8), -1, config,
                BoxRelocation.ALL_SLOTS), "A disabled empty box is not a relocation destination");
        assertFalse(ManagedJunkBoxes.marked(inventory.getItem(9)), "No managed role is created");
        assertEquals(0, count(inventory.getItem(9), Items.STONE));

        config.useEmptyBoxes = true;
        assertEquals(9, BoxRelocation.store(inventory, new ItemStack(Items.STONE, 8), -1, config,
                BoxRelocation.ALL_SLOTS));
        assertTrue(ManagedJunkBoxes.marked(inventory.getItem(9)));
        assertEquals(8, count(inventory.getItem(9), Items.STONE));
    }

    @Test void existingManagedJunkBoxStillWorksWhenEmptyBoxesAreDisabled() {
        var inventory = new SimpleContainer(41);
        var junk = box();
        ManagedJunkBoxes.mark(junk);
        inventory.setItem(9, junk);
        var config = new StorageConfig();
        config.useEmptyBoxes = false;
        assertEquals(9, BoxRelocation.store(inventory, new ItemStack(Items.STONE, 8), -1, config,
                BoxRelocation.ALL_SLOTS));
        assertTrue(ManagedJunkBoxes.marked(inventory.getItem(9)));
        assertEquals(8, count(inventory.getItem(9), Items.STONE));
    }

    @Test void matchingBoxStillWorksWhenEmptyBoxesAreDisabled() {
        var inventory = new SimpleContainer(41);
        inventory.setItem(9, box(new ItemStack(Items.DIRT, 3)));
        var config = new StorageConfig();
        config.useEmptyBoxes = false;
        assertEquals(9, BoxRelocation.store(inventory, new ItemStack(Items.DIRT, 8), -1, config,
                BoxRelocation.ALL_SLOTS));
        assertEquals(11, count(inventory.getItem(9), Items.DIRT));
    }

    private static ItemStack box(ItemStack... contents) {
        var box = new ItemStack(Items.SHULKER_BOX);
        box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(contents)));
        return box;
    }

    private static int count(ItemStack box, net.minecraft.world.item.Item item) {
        return box.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).stream()
                .filter(stack -> stack.is(item)).mapToInt(ItemStack::getCount).sum();
    }
}
