package dev.magicshulkerboxes.client;

import dev.magicshulkerboxes.CraftingRecipeSources;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;

/** Let the vanilla recipe book display box-backed recipes only when the server advertises the feature. */
public final class CraftingRefillClient {
    private static final ItemContainerContents[] previousContents = new ItemContainerContents[41];
    private static final Item[] previousItems = new Item[41];
    private static final int[] previousCounts = new int[41];
    private CraftingRefillClient() {}
    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || !(client.screen instanceof AbstractRecipeBookScreen<?> screen)
                    || !(screen.getMenu() instanceof net.minecraft.world.inventory.AbstractCraftingMenu)) return;
            boolean changed = false;
            var inventory = client.player.getInventory();
            for (int slot = 0; slot < 41; slot++) {
                if (slot >= 36 && slot != 40) continue;
                var stack = inventory.getItem(slot);
                var contents = stack.get(DataComponents.CONTAINER);
                if (contents != previousContents[slot] || stack.getItem() != previousItems[slot] || stack.getCount() != previousCounts[slot]) {
                    changed = true; previousContents[slot] = contents; previousItems[slot] = stack.getItem(); previousCounts[slot] = stack.getCount();
                }
            }
            // Box contents can change without changing the outer count. Tell vanilla's recipe book to recount.
            if (changed) inventory.setChanged();
        });
    }
    public static void account(Inventory inventory, StackedItemContents contents) {
        var client = Minecraft.getInstance();
        if (client.player == null || client.player.isCreative() || client.player.isSpectator()
                || inventory != client.player.getInventory() || !ClientSettings.craftingSupported()
                || !RefillSettings.get().craftRefill || !(client.player.containerMenu instanceof InventoryMenu
                || client.player.containerMenu instanceof CraftingMenu)) return;
        CraftingRecipeSources.accountBoxes(dev.magicshulkerboxes.RefillSources.of(client.player, RefillSettings.get()), contents, RefillSettings.get(), box -> true);
    }
}
