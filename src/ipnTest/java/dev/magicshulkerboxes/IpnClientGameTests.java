package dev.magicshulkerboxes;

import dev.magicshulkerboxes.client.IpnCandidates;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.GameType;
import org.anti_ad.mc.ipnext.config.AutoRefillSettings;
import org.anti_ad.mc.ipnext.config.Features;
import org.anti_ad.mc.ipnext.config.ThresholdUnit;
import org.anti_ad.mc.ipnext.event.autorefill.AutoRefillHandler;
import org.anti_ad.mc.ipnext.event.LockSlotsHandler;

/** Runs the released IPN matcher and monitor Mixins against a real, isolated client/server connection. */
public class IpnClientGameTests implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        try (var world = context.worldBuilder().create()) { runFlows(context, world.getServer()); }
    }

    public static void runFlows(ClientGameTestContext context, TestServerContext world) {
        context.runOnClient(client -> {
            Features.INSTANCE.getENABLE_AUTO_REFILL().setValue(false);
            AutoRefillSettings.INSTANCE.getAUTO_REFILL_WAIT_TICK().setValue(0);
        });
        world.runOnServer(server -> {
            MagicShulkerBoxes.config().ipnRefill = true;
            MagicShulkerBoxes.config().allowPlayerSettings = false;
            server.getPlayerList().getPlayers().getFirst().setGameMode(GameType.SURVIVAL);
        });
        selectionRules(context, world);
        lockedSources(context, world);
        backpackPriority(context, world);
        potionFlow(context, world);
        offhandFlow(context, world);
        toolFlow(context, world);
        disabledSlot(context, world);
        enderFlows(context, world);
        context.takeScreenshot("ipn-restock-verified");
    }

    private static void selectionRules(ClientGameTestContext context, TestServerContext world) {
        var strength = potion(Potions.STRENGTH); var speed = potion(Potions.SWIFTNESS);
        prepare(context, world, new ItemStack(Items.GLASS_BOTTLE), box(speed, strength), ItemStack.EMPTY);
        context.runOnClient(client -> {
            var match = IpnCandidates.find(IpnCandidates.wrap(strength), IpnCandidates.wrap(new ItemStack(Items.GLASS_BOTTLE)));
            check(match != null && match.contentSlot() == 1, "IPN chooses potion effects, not merely the potion item ID");
            check(client.player.getInventory().getItem(10).isEmpty(), "Candidate lookup never edits client inventory");
        });
        var named = new ItemStack(Items.STONE); named.set(DataComponents.CUSTOM_NAME, Component.literal("Named"));
        prepare(context, world, ItemStack.EMPTY, box(new ItemStack(Items.STONE), named), ItemStack.EMPTY);
        context.runOnClient(client -> {
            AutoRefillSettings.INSTANCE.getAUTO_REFILL_MATCH_CUSTOM_NAME().setValue(true);
            var match = IpnCandidates.find(IpnCandidates.wrap(named), IpnCandidates.wrap(ItemStack.EMPTY));
            check(match != null && match.contentSlot() == 1, "IPN's custom-name option filters box contents");
            AutoRefillSettings.INSTANCE.getAUTO_REFILL_MATCH_CUSTOM_NAME().setValue(false);
            match = IpnCandidates.find(IpnCandidates.wrap(named), IpnCandidates.wrap(ItemStack.EMPTY));
            check(match != null, "IPN can relax its matching without a second local policy");
        });
    }

    private static void potionFlow(ClientGameTestContext context, TestServerContext world) {
        var strength = potion(Potions.STRENGTH);
        prepare(context, world, strength, box(strength), ItemStack.EMPTY);
        initializeMonitor(context);
        world.runOnServer(server -> {
            var player = server.getPlayerList().getPlayers().getFirst();
            player.getInventory().setItem(0, new ItemStack(Items.GLASS_BOTTLE)); player.inventoryMenu.broadcastChanges();
        });
        context.waitFor(client -> client.player.getMainHandItem().is(Items.GLASS_BOTTLE));
        drive(context, client -> client.player.getMainHandItem().is(Items.POTION));
        world.runOnServer(server -> {
            var player = server.getPlayerList().getPlayers().getFirst();
            check(player.getMainHandItem().is(Items.POTION), "IPN swapped the extracted potion through normal server clicks");
            int bottles = 0;
            for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
                var stack = player.getInventory().getItem(slot);
                if (stack.is(Items.GLASS_BOTTLE)) bottles += stack.getCount();
            }
            check(bottles == 1, "Empty bottle was preserved wherever IPN chose to put it");
            check(player.getInventory().getItem(9).get(DataComponents.CONTAINER).stream().noneMatch(stack -> !stack.isEmpty()),
                    "One potion was taken from the box");
        });
    }

    private static void backpackPriority(ClientGameTestContext context, TestServerContext world) {
        var strength = potion(Potions.STRENGTH);
        prepare(context, world, strength, box(strength), strength);
        initializeMonitor(context);
        world.runOnServer(server -> {
            var player = server.getPlayerList().getPlayers().getFirst();
            player.getInventory().setItem(0, new ItemStack(Items.GLASS_BOTTLE)); player.inventoryMenu.broadcastChanges();
        });
        context.waitFor(client -> client.player.getMainHandItem().is(Items.GLASS_BOTTLE));
        drive(context, client -> client.player.getMainHandItem().is(Items.POTION));
        world.runOnServer(server -> check(server.getPlayerList().getPlayers().getFirst().getInventory()
                .getItem(9).get(DataComponents.CONTAINER).stream().anyMatch(stack -> stack.is(Items.POTION)),
                "Existing backpack candidate wins without extracting from the box"));
    }

    private static void toolFlow(ClientGameTestContext context, TestServerContext world) {
        var worn = new ItemStack(Items.DIAMOND_PICKAXE); worn.setDamageValue(worn.getMaxDamage() - 1);
        var spare = new ItemStack(Items.IRON_PICKAXE);
        prepare(context, world, worn, box(spare), ItemStack.EMPTY);
        context.runOnClient(client -> {
            AutoRefillSettings.INSTANCE.getREFILL_BEFORE_TOOL_BREAK().setValue(true);
            AutoRefillSettings.INSTANCE.getTHRESHOLD_UNIT().setValue(ThresholdUnit.ABSOLUTE);
            AutoRefillSettings.INSTANCE.getTOOL_DAMAGE_THRESHOLD().setValue(5);
            var match = IpnCandidates.find(IpnCandidates.wrap(worn), IpnCandidates.wrap(worn));
            check(match != null && match.stack().is(Items.IRON_PICKAXE), "IPN's same-tool-category fallback is retained");
        });
        initializeMonitor(context);
        drive(context, client -> client.player.getMainHandItem().is(Items.IRON_PICKAXE));
        world.runOnServer(server -> {
            var player = server.getPlayerList().getPlayers().getFirst();
            var contents = net.minecraft.core.NonNullList.withSize(27, ItemStack.EMPTY);
            player.getInventory().getItem(9).get(DataComponents.CONTAINER).copyInto(contents);
            check(ItemStack.matches(contents.get(0), worn), "Protected tool returned to the source box's original slot with its components");
            check(player.getInventory().getItem(10).isEmpty(), "Tool exchange needs no temporary backpack slot");
        });
    }

    private static void enderFlows(ClientGameTestContext context, TestServerContext world) {
        var strength = potion(Potions.STRENGTH);
        prepare(context, world, strength, ItemStack.EMPTY, ItemStack.EMPTY);
        world.runOnServer(server -> {
            MagicShulkerBoxes.config().enderChestRefill = true;
            var player = server.getPlayerList().getPlayers().getFirst();
            player.getEnderChestInventory().clearContent(); player.getEnderChestInventory().setItem(0, strength.copy());
            SettingsNetwork.broadcastPolicy(server);
        });
        context.waitFor(client -> ItemStack.matches(client.player.getEnderChestInventory().getItem(0), strength));
        initializeMonitor(context);
        world.runOnServer(server -> {
            var p=server.getPlayerList().getPlayers().getFirst();p.getInventory().setItem(0,new ItemStack(Items.GLASS_BOTTLE));p.inventoryMenu.broadcastChanges();
        });
        context.waitFor(client -> client.player.getMainHandItem().is(Items.GLASS_BOTTLE));
        drive(context,client -> client.player.getMainHandItem().is(Items.POTION));
        world.runOnServer(server -> check(server.getPlayerList().getPlayers().getFirst().getEnderChestInventory().getItem(0).isEmpty(),"Actual IPN potion flow takes one owned ender item"));

        var worn = new ItemStack(Items.DIAMOND_PICKAXE); worn.setDamageValue(worn.getMaxDamage()-1);
        worn.set(DataComponents.CUSTOM_NAME,Component.literal("Protected ender pick"));
        var spare = new ItemStack(Items.IRON_PICKAXE);
        prepare(context,world,worn,ItemStack.EMPTY,ItemStack.EMPTY);
        world.runOnServer(server -> {
            var contents=net.minecraft.core.NonNullList.withSize(27,ItemStack.EMPTY);contents.set(5,spare.copy());
            var source=box();source.set(DataComponents.CONTAINER,ItemContainerContents.fromItems(contents));
            server.getPlayerList().getPlayers().getFirst().getEnderChestInventory().setItem(2,source);
        });
        context.waitFor(client -> ShulkerStorage.isShulker(client.player.getEnderChestInventory().getItem(2)));
        context.runOnClient(client -> AutoRefillSettings.INSTANCE.getAUTO_REFILL_MATCH_CUSTOM_NAME().setValue(false));
        initializeMonitor(context);drive(context,client -> client.player.getMainHandItem().is(Items.IRON_PICKAXE));
        world.runOnServer(server -> {
            var p=server.getPlayerList().getPlayers().getFirst();var contents=net.minecraft.core.NonNullList.withSize(27,ItemStack.EMPTY);
            p.getEnderChestInventory().getItem(2).get(DataComponents.CONTAINER).copyInto(contents);
            check(ItemStack.matches(contents.get(5),worn),"Old tool returns to exact slot inside the ender source box");
        });
    }

    @SuppressWarnings("unchecked") // IPN's optimized release exposes its Kotlin collections as raw Java types.
    private static void lockedSources(ClientGameTestContext context, TestServerContext world) {
        var strength = potion(Potions.STRENGTH);
        prepare(context, world, ItemStack.EMPTY, box(strength), ItemStack.EMPTY);
        context.runOnClient(client -> {
            Features.INSTANCE.getENABLE_LOCK_SLOTS().setValue(true);
            AutoRefillSettings.INSTANCE.getIGNORE_LOCKED_SLOTS().setValue(false);
            LockSlotsHandler.INSTANCE.getLockedInvSlotsStoredValue().add(9);
            check(IpnCandidates.find(IpnCandidates.wrap(strength), IpnCandidates.wrap(ItemStack.EMPTY)) == null,
                    "IPN's locked source box is excluded");
            AutoRefillSettings.INSTANCE.getIGNORE_LOCKED_SLOTS().setValue(true);
            check(IpnCandidates.find(IpnCandidates.wrap(strength), IpnCandidates.wrap(ItemStack.EMPTY)) != null,
                    "IPN can opt to include locked sources");
            LockSlotsHandler.INSTANCE.getLockedInvSlotsStoredValue().remove(9);
            AutoRefillSettings.INSTANCE.getIGNORE_LOCKED_SLOTS().setValue(false);
        });
    }

    private static void offhandFlow(ClientGameTestContext context, TestServerContext world) {
        var strength = potion(Potions.STRENGTH);
        prepare(context, world, ItemStack.EMPTY, box(strength), ItemStack.EMPTY);
        world.runOnServer(server -> {
            var player = server.getPlayerList().getPlayers().getFirst();
            player.getInventory().setItem(40, strength.copy()); player.inventoryMenu.broadcastChanges();
        });
        context.waitFor(client -> client.player.getOffhandItem().is(Items.POTION));
        initializeMonitor(context);
        world.runOnServer(server -> {
            var player = server.getPlayerList().getPlayers().getFirst();
            player.getInventory().setItem(40, new ItemStack(Items.GLASS_BOTTLE)); player.inventoryMenu.broadcastChanges();
        });
        context.waitFor(client -> client.player.getOffhandItem().is(Items.GLASS_BOTTLE));
        drive(context, client -> client.player.getOffhandItem().is(Items.POTION));
        world.runOnServer(server -> check(server.getPlayerList().getPlayers().getFirst().getOffhandItem().is(Items.POTION),
                "IPN's original offhand click sequence accepts the extracted potion"));
    }

    @SuppressWarnings("unchecked") // IPN's optimized release exposes its Kotlin collections as raw Java types.
    private static void disabledSlot(ClientGameTestContext context, TestServerContext world) {
        var strength = potion(Potions.STRENGTH);
        prepare(context, world, strength, box(strength), ItemStack.EMPTY);
        context.runOnClient(client -> {
            AutoRefillSettings.INSTANCE.getAUTO_REFILL_ENABLE_PER_SLOT_CONFIG().setValue(true);
            AutoRefillHandler.INSTANCE.getDisabledSlots().add(0);
        });
        initializeMonitor(context);
        world.runOnServer(server -> {
            var player = server.getPlayerList().getPlayers().getFirst();
            player.getInventory().setItem(0, new ItemStack(Items.GLASS_BOTTLE)); player.inventoryMenu.broadcastChanges();
        });
        context.waitFor(client -> client.player.getMainHandItem().is(Items.GLASS_BOTTLE));
        for (int i = 0; i < 20; i++) {
            context.runOnClient(client -> AutoRefillHandler.INSTANCE.handleAutoRefill()); context.waitTick();
        }
        context.runOnClient(client -> {
            check(client.player.getMainHandItem().is(Items.GLASS_BOTTLE), "IPN disabled slot does not trigger box extraction");
            check(client.player.getInventory().getItem(10).isEmpty(), "Disabled slot retained source potion");
            AutoRefillHandler.INSTANCE.getDisabledSlots().remove(0);
        });
    }

    private static void prepare(ClientGameTestContext context, TestServerContext world,
                                ItemStack hand, ItemStack source, ItemStack backpack) {
        world.runOnServer(server -> {
            var player = server.getPlayerList().getPlayers().getFirst(); var inventory = player.getInventory();
            inventory.clearContent(); inventory.setSelectedSlot(0);
            inventory.setItem(0, hand.copy()); inventory.setItem(9, source.copy()); inventory.setItem(10, backpack.copy());
            player.inventoryMenu.broadcastChanges();
        });
        context.waitFor(client -> ItemStack.matches(client.player.getMainHandItem(), hand)
                && ItemStack.matches(client.player.getInventory().getItem(9), source)
                && ItemStack.matches(client.player.getInventory().getItem(10), backpack));
    }
    private static void initializeMonitor(ClientGameTestContext context) {
        context.runOnClient(client -> { AutoRefillHandler.INSTANCE.init(); AutoRefillHandler.INSTANCE.handleAutoRefill(); });
    }
    private static void drive(ClientGameTestContext context, java.util.function.Predicate<net.minecraft.client.Minecraft> done) {
        context.waitFor(client -> { AutoRefillHandler.INSTANCE.handleAutoRefill(); return done.test(client); });
        context.waitTicks(3);
    }
    private static ItemStack potion(net.minecraft.core.Holder<net.minecraft.world.item.alchemy.Potion> type) {
        var stack = new ItemStack(Items.POTION); stack.set(DataComponents.POTION_CONTENTS, new PotionContents(type)); return stack;
    }
    private static ItemStack box(ItemStack... items) {
        var stack = new ItemStack(Items.SHULKER_BOX);
        stack.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(items))); return stack;
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
