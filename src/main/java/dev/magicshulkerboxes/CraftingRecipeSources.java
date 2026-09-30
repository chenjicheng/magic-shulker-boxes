package dev.magicshulkerboxes;

import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.RecipeBookMenu.PostPlaceAction;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.GameType;

/** Scope virtual material accounting to one vanilla placement; commit source changes only after the whole grid is filled. */
public final class CraftingRecipeSources {
    private static final ThreadLocal<Placement> ACTIVE = new ThreadLocal<>();
    private CraftingRecipeSources() {}

    public static boolean allowed(ServerPlayer player, AbstractCraftingMenu menu) {
        var mode = player.gameMode.getGameModeForPlayer();
        return (menu instanceof InventoryMenu || menu instanceof CraftingMenu) && player.containerMenu == menu
                && player.isAlive() && (mode == GameType.SURVIVAL || mode == GameType.ADVENTURE) && menu.stillValid(player)
                && MagicShulkerBoxes.configFor(player).craftRefill;
    }

    public static PostPlaceAction place(AbstractCraftingMenu menu, Inventory inventory, RecipeHolder<?> holder,
                                       ServerLevel level, boolean all, Supplier<PostPlaceAction> vanilla) {
        if (!(inventory.player instanceof ServerPlayer player) || !allowed(player, menu)
                || !(holder.value() instanceof CraftingRecipe recipe) || recipe.placementInfo().isImpossibleToPlace()) return vanilla.get();
        var placement = new Placement(menu, inventory, recipe, level, all, MagicShulkerBoxes.configFor(player));
        // Vanilla's coarse space check can group incompatible components. Prove the old inputs fit without dropping anything.
        var space = CraftingMaterials.copy(inventory);
        for (var stack : placement.beforeGrid) if (!CraftingMaterials.putBack(space, stack.copy())) return PostPlaceAction.NOTHING;
        var previous = ACTIVE.get(); ACTIVE.set(placement);
        try {
            var result = vanilla.get();
            if (placement.failed || (placement.usedSources && !placement.commit())) { placement.restore(); return PostPlaceAction.NOTHING; }
            return result;
        } catch (RuntimeException | Error exception) {
            if (placement.usedSources || placement.failed) placement.restore();
            throw exception;
        } finally {
            if (previous == null) ACTIVE.remove(); else ACTIVE.set(previous);
        }
    }

    public static void account(Inventory inventory, StackedItemContents contents) {
        var placement = ACTIVE.get();
        if (placement == null || placement.inventory != inventory) return;
        int wanted = 1;
        if (placement.recipe.matches(placement.input(), placement.level)) {
            wanted = placement.beforeGrid.stream().filter(stack -> !stack.isEmpty()).mapToInt(ItemStack::getCount).min().orElse(0) + 1;
        }
        if (!placement.all && contents.canCraft(placement.recipe, wanted, null)) return;
        accountBoxes(placement.planned, contents, placement.config, placement.allowedBox);
    }

    /** Recipe-book projections share vanilla's usable-item filter; actual placement is always checked by the server. */
    public static void accountBoxes(Container inventory, StackedItemContents contents, StorageConfig config, Predicate<ItemStack> allowedBox) {
        for (int slot : BoxOrder.emptiestFirst(inventory, config)) {
            var box = inventory.getItem(slot);
            if (!allowedBox.test(box) || (box.getCount() > 1 && (!config.splitStackedBoxes || CraftingMaterials.freeSlot(inventory) < 0))) continue;
            box.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).stream()
                    .filter(stack -> !stack.isEmpty() && stack.getItem().canFitInsideContainerItems()).forEach(contents::accountSimpleStack);
        }
    }

    public static int takeMissing(Inventory inventory, Slot slot, Holder<Item> item, int amount) {
        var placement = ACTIVE.get();
        if (placement == null || placement.inventory != inventory) return -1;
        var current = slot.getItem();
        var taken = CraftingMaterials.takeBox(placement.planned, stack -> stack.is(item) && Inventory.isUsableForCrafting(stack)
                && (current.isEmpty() || ItemStack.isSameItemSameComponents(current, stack)), amount, placement.config, placement.allowedBox);
        if (taken == null) { placement.failed = true; return -1; }
        placement.usedSources = true;
        int total = current.getCount() + taken.stack().getCount();
        if (total > taken.stack().getMaxStackSize()) { placement.failed = true; return -1; }
        slot.set(current.isEmpty() ? taken.stack() : current.copyWithCount(total));
        return amount - taken.stack().getCount();
    }

    private static final class Placement {
        final AbstractCraftingMenu menu;
        final Inventory inventory;
        final CraftingRecipe recipe;
        final ServerLevel level;
        final boolean all;
        final StorageConfig config;
        final SimpleContainer beforeInventory, planned;
        final List<ItemStack> beforeGrid;
        final Predicate<ItemStack> allowedBox;
        boolean usedSources, failed;
        Placement(AbstractCraftingMenu menu, Inventory inventory, CraftingRecipe recipe, ServerLevel level, boolean all, StorageConfig config) {
            this.menu = menu; this.inventory = inventory; this.recipe = recipe; this.level = level; this.all = all; this.config = config;
            beforeInventory = CraftingMaterials.copy(inventory); planned = CraftingMaterials.copy(inventory);
            beforeGrid = menu.getInputGridSlots().stream().map(slot -> slot.getItem().copy()).toList();
            // A box must not simultaneously supply its contents and become an ingredient itself.
            allowedBox = box -> recipe.placementInfo().ingredients().stream().noneMatch(ingredient -> ingredient.test(box));
        }
        CraftingInput input() {
            return CraftingInput.of(menu.getGridWidth(), menu.getGridHeight(), menu.getInputGridSlots().stream().map(Slot::getItem).toList());
        }
        boolean commit() {
            if (failed || !recipe.matches(input(), level)) return false;
            for (int i = 0; i < planned.getContainerSize(); i++) {
                if (!ItemStack.matches(beforeInventory.getItem(i), planned.getItem(i))
                        && !ItemStack.matches(beforeInventory.getItem(i), inventory.getItem(i))) return false;
            }
            CraftingMaterials.commitChanges(beforeInventory, planned, inventory);
            return true;
        }
        void restore() {
            for (int i = 0; i < beforeInventory.getContainerSize(); i++) CraftingMaterials.write(inventory, i, beforeInventory.getItem(i));
            for (int i = 0; i < beforeGrid.size(); i++) menu.getInputGridSlots().get(i).set(beforeGrid.get(i).copy());
            inventory.setChanged();
        }
    }
}
