package dev.magicshulkerboxes;

import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RefillSearchTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
    @Test void matchesComponentsAndRespectsOffhandPolicy() {
        var inv = new SimpleContainer(41);
        var named = new ItemStack(Items.STONE); named.set(DataComponents.CUSTOM_NAME, Component.literal("Keep"));
        inv.setItem(0, box(named)); inv.setItem(40, box(new ItemStack(Items.STONE)));
        var config = new StorageConfig();
        assertNull(RefillSearch.find(inv, new ItemStack(Items.STONE), config));
        config.includeOffhand = true;
        assertEquals(new RefillSearch.Match(40, 0), RefillSearch.find(inv, new ItemStack(Items.STONE), config));
        assertEquals("Keep", inv.getItem(0).get(DataComponents.CONTAINER).stream().findFirst().orElseThrow().getHoverName().getString());
    }

    @Test void findsLateMaterialWithoutMutatingAnyBoxAndReportsSearchTiming() {
        var inv = new SimpleContainer(41);
        var stacks = new ItemStack[27]; java.util.Arrays.setAll(stacks, i -> new ItemStack(Items.STONE, 64));
        for (int i = 0; i < 36; i++) inv.setItem(i, box(stacks));
        var config = new StorageConfig(); var missing = new ItemStack(Items.GLASS);
        for (int i = 0; i < 200; i++) assertNull(RefillSearch.find(inv, missing, config));
        var samples = new long[1000];
        for (int i = 0; i < samples.length; i++) {
            long start = System.nanoTime();
            var match = RefillSearch.find(inv, missing, config);
            samples[i] = System.nanoTime() - start;
            assertNull(match);
        }
        java.util.Arrays.sort(samples);
        System.out.printf("REFILL_SEARCH_972_SLOTS median_us=%.3f p95_us=%.3f%n", samples[500] / 1000.0, samples[950] / 1000.0);
        stacks[26] = missing;
        inv.setItem(35, box(stacks));
        assertEquals(new RefillSearch.Match(35, 26), RefillSearch.find(inv, missing, config));
        assertEquals(1728, inv.getItem(0).get(DataComponents.CONTAINER).stream().mapToInt(ItemStack::getCount).sum());
    }

    private static ItemStack box(ItemStack... stacks) {
        var result = new ItemStack(Items.SHULKER_BOX);
        result.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(stacks)));
        return result;
    }
}
