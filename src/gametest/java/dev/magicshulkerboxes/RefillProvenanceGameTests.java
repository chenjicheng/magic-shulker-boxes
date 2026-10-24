package dev.magicshulkerboxes;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.GameType;

public class RefillProvenanceGameTests {
    @GameTest public void replacedMaterialReturnsToItsOriginalBox(GameTestHelper helper) {
        var player = player(helper);
        var inventory = player.getInventory();
        inventory.setItem(35, box(new ItemStack(Items.STONE, 12)));
        check(helper, RefillNetwork.accept(player, request(helper, 35, 0, "minecraft:stone")) == 12,
                "The first refill places stone in the selected hotbar slot");
        check(helper, inventory.getItem(0).is(Items.STONE) && inventory.getItem(0).getCount() == 12,
                "The selected slot holds the refill");
        check(helper, RefillProvenance.view(player.getUUID()).size() == 1, "The refill is recorded");

        for (int slot = 1; slot < 34; slot++) inventory.setItem(slot, new ItemStack(Items.DIRT, 64));
        inventory.setItem(34, box(new ItemStack(Items.COBBLESTONE, 12)));
        check(helper, ShulkerRefill.take(inventory, 34, 0, Items.COBBLESTONE,
                MagicShulkerBoxes.configFor(player)) == 12, "The second refill replaces the selected stack");

        check(helper, inventory.getItem(0).is(Items.COBBLESTONE) && inventory.getItem(0).getCount() == 12,
                "The new material takes the selected slot");
        check(helper, count(inventory.getItem(35), Items.STONE) == 12,
                "The replaced stone returns to its original box");
        check(helper, count(inventory.getItem(34), Items.COBBLESTONE) == 0, "The second source box is emptied");
        var entries = RefillProvenance.view(player.getUUID());
        check(helper, entries.size() == 1 && entries.getFirst().anchorSlot() == 0
                        && entries.getFirst().sourceSlot() == 34
                        && entries.getFirst().prototype().is(Items.COBBLESTONE)
                        && entries.getFirst().remaining() == 12,
                "Only the new cobblestone entry remains");
        helper.succeed();
    }

    @GameTest public void partialConsumptionReturnsOnlyTheRemainingMarkedCount(GameTestHelper helper) {
        var player = player(helper);
        var inventory = player.getInventory();
        inventory.setItem(35, box(new ItemStack(Items.STONE, 12)));
        check(helper, RefillNetwork.accept(player, request(helper, 35, 0, "minecraft:stone")) == 12,
                "The first refill places stone in the selected hotbar slot");
        inventory.getItem(0).shrink(5);

        for (int slot = 1; slot < 34; slot++) inventory.setItem(slot, new ItemStack(Items.DIRT, 64));
        inventory.setItem(34, box(new ItemStack(Items.COBBLESTONE, 12)));
        check(helper, ShulkerRefill.take(inventory, 34, 0, Items.COBBLESTONE,
                MagicShulkerBoxes.configFor(player)) == 12, "The replacement succeeds");
        check(helper, inventory.getItem(0).is(Items.COBBLESTONE), "The new material takes the selected slot");
        check(helper, count(inventory.getItem(35), Items.STONE) == 7,
                "Only the seven unused blocks return to the source box");
        helper.succeed();
    }

    @GameTest public void movingWithinTheHotbarFollowsTheMarkedStack(GameTestHelper helper) {
        var player = player(helper);
        var inventory = player.getInventory();
        inventory.setItem(35, box(new ItemStack(Items.STONE, 12)));
        check(helper, RefillNetwork.accept(player, request(helper, 35, 0, "minecraft:stone")) == 12,
                "The first refill places stone in the selected hotbar slot");
        var stone = inventory.getItem(0).copy();
        inventory.setItem(0, ItemStack.EMPTY);
        inventory.setItem(3, stone);
        for (int slot = 0; slot < 9; slot++) {
            if (slot != 3) inventory.setItem(slot, new ItemStack(Items.DIRT, 64));
        }
        inventory.setSelectedSlot(3);

        for (int slot = 9; slot < 34; slot++) inventory.setItem(slot, new ItemStack(Items.DIRT, 64));
        inventory.setItem(34, box(new ItemStack(Items.COBBLESTONE, 12)));
        check(helper, ShulkerRefill.take(inventory, 34, 0, Items.COBBLESTONE,
                MagicShulkerBoxes.configFor(player)) == 12, "The moved stack is still replaced");
        check(helper, inventory.getItem(3).is(Items.COBBLESTONE), "The new material takes the moved slot");
        check(helper, count(inventory.getItem(35), Items.STONE) == 12,
                "The moved stone returns to its original box");
        var entries = RefillProvenance.view(player.getUUID());
        check(helper, entries.size() == 1 && entries.getFirst().anchorSlot() == 3
                        && entries.getFirst().prototype().is(Items.COBBLESTONE),
                "Provenance follows the unique moved hotbar stack");
        helper.succeed();
    }

    @GameTest public void existingSourceBoxReturnsEvenWhenEmptyBoxesAreDisabled(GameTestHelper helper) {
        var config = MagicShulkerBoxes.config();
        boolean previous = config.useEmptyBoxes;
        try {
            var player = player(helper);
            var inventory = player.getInventory();
            inventory.setItem(35, box(new ItemStack(Items.STONE, 12)));
            check(helper, RefillNetwork.accept(player, request(helper, 35, 0, "minecraft:stone")) == 12,
                    "The first refill places stone in the selected hotbar slot");
            for (int slot = 1; slot < 34; slot++) inventory.setItem(slot, new ItemStack(Items.DIRT, 64));
            inventory.setItem(34, box(new ItemStack(Items.COBBLESTONE, 12)));
            config.useEmptyBoxes = false;
            check(helper, ShulkerRefill.take(inventory, 34, 0, Items.COBBLESTONE,
                    MagicShulkerBoxes.configFor(player)) == 12, "The original box may receive its material");
            check(helper, count(inventory.getItem(35), Items.STONE) == 12,
                    "An existing source box is not blocked by the empty-box switch");
            check(helper, !ManagedJunkBoxes.marked(inventory.getItem(35)),
                    "Returning to the original box does not create a managed role");
        } finally {
            config.useEmptyBoxes = previous;
        }
        helper.succeed();
    }

    @GameTest public void ledgerSnapshotCodecRoundTrips(GameTestHelper helper) {
        var snapshot = new RefillProvenanceNetwork.Snapshot(List.of(
                new RefillProvenanceNetwork.WireEntry(0, 35, false, 12, "a".repeat(64)),
                new RefillProvenanceNetwork.WireEntry(3, 100, true, 4, "b".repeat(64))));
        var buffer = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),
                helper.getLevel().registryAccess());
        RefillProvenanceNetwork.Snapshot.CODEC.encode(buffer, snapshot);
        check(helper, RefillProvenanceNetwork.Snapshot.CODEC.decode(buffer).equals(snapshot),
                "The provenance projection survives its network codec");
        helper.succeed();
    }

    @GameTest public void ledgerCommandReportsTheCurrentEntries(GameTestHelper helper) throws Exception {
        var player = player(helper);
        player.getInventory().setItem(35, box(new ItemStack(Items.STONE, 12)));
        check(helper, RefillNetwork.accept(player, request(helper, 35, 0, "minecraft:stone")) == 12,
                "The refill is recorded");
        var output = new Output();
        var source = player.createCommandSourceStack().withSource(output).withPermission(LevelBasedPermissionSet.ALL);
        check(helper, execute(source, "msb ledger") == 1, "The player can read the ledger");
        var text = output.text();
        check(helper, text.contains(new ItemStack(Items.STONE).getHoverName().getString()) && text.contains("12"),
                "The ledger shows the marked item and remaining count");

        var admin = helper.getLevel().getServer().createCommandSourceStack().withSource(output)
                .withPermission(LevelBasedPermissionSet.ADMIN);
        check(helper, execute(admin, "msb admin ledger " + player.getUUID()) == 1,
                "An administrator can read another player's online ledger");
        helper.succeed();
    }

    @SuppressWarnings("removal") private static ServerPlayer player(GameTestHelper helper) {
        var player = helper.makeMockServerPlayerInLevel();
        player.setGameMode(GameType.SURVIVAL);
        player.getInventory().clearContent();
        player.getInventory().setSelectedSlot(0);
        return player;
    }

    private static void check(GameTestHelper helper, boolean value, String message) {
        helper.assertTrue(value, Component.literal(message));
    }

    private static RefillNetwork.Request request(GameTestHelper helper, int boxSlot, int contentSlot, String item) {
        var stack = new ItemStack(BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.parse(item)));
        return new RefillNetwork.Request(boxSlot, contentSlot, item,
                ItemFingerprint.of(stack, helper.getLevel().registryAccess()));
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

    private static int execute(CommandSourceStack source, String command) throws Exception {
        return source.getServer().getCommands().getDispatcher().execute(command, source);
    }

    private static final class Output implements CommandSource {
        final List<Component> components = new ArrayList<>();
        String text() {
            return components.stream().map(Component::getString).reduce("", (left, right) -> left + "\n" + right);
        }
        @Override public void sendSystemMessage(Component message) { components.add(message); }
        @Override public boolean acceptsSuccess() { return true; }
        @Override public boolean acceptsFailure() { return true; }
        @Override public boolean shouldInformAdmins() { return false; }
    }
}
