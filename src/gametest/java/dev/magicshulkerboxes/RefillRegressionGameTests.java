package dev.magicshulkerboxes;

import dev.magicshulkerboxes.client.SettingsSession;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.GameType;

/** Regression fixtures for stale requests, save recovery and blocked refill planning. */
public class RefillRegressionGameTests {
    @GameTest public void changedComponentsMustNotExtractDifferentMaterials(GameTestHelper helper) {
        var player = player(helper);
        var plain = new ItemStack(Items.STONE, 12);
        player.getInventory().setItem(9, box(List.of(plain)));
        var match = RefillSearch.find(player.getInventory(), plain, MagicShulkerBoxes.configFor(player));
        helper.assertTrue(match != null, Component.literal("Client lookup selects plain stone"));
        var request = request(helper, match.boxSlot(), match.contentSlot(), "minecraft:stone");
        var renamed = plain.copy();
        renamed.set(DataComponents.CUSTOM_NAME, Component.literal("Reserved named material"));
        // The server slot changes after lookup but before the queued request is handled.
        player.getInventory().setItem(9, box(List.of(renamed)));
        int moved = RefillNetwork.accept(player, request);
        helper.assertTrue(moved == 0, Component.literal("Changed components should invalidate request; moved=" + moved));
        helper.assertTrue(player.getInventory().getItem(10).isEmpty()
                && player.getInventory().getItem(9).get(DataComponents.CONTAINER).stream().mapToInt(ItemStack::getCount).sum() == 12,
                Component.literal("Rejected stale request leaves materials in the box"));
        helper.succeed();
    }

    @GameTest public void unchangedNamedMaterialRemainsExtractable(GameTestHelper helper) {
        var player = player(helper);
        var named = new ItemStack(Items.STONE, 12);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("Materials"));
        player.getInventory().setItem(9, box(List.of(named)));
        var request = new RefillNetwork.Request(9, 0, "minecraft:stone", ItemFingerprint.of(named, player.registryAccess()));
        helper.assertTrue(RefillNetwork.accept(player, request) == 12, Component.literal("Matching non-default components are accepted"));
        helper.assertTrue(ItemStack.isSameItemSameComponents(named, player.getInventory().getItem(10)),
                Component.literal("Extracted components come from the server's source"));
        helper.succeed();
    }

    @GameTest public void recoveryQueryIsReadOnlyIsolatedAndRateLimited(GameTestHelper helper) throws Exception {
        var first = player(helper); var second = player(helper);
        var store = MagicShulkerBoxes.players(helper.getLevel().getServer());
        store.save(first.getUUID(), ConfigFile.parsePreferences("{\"schematicRefill\":false}"));
        var reply = EditorNetwork.query(first, new EditorNetwork.Query(1));
        helper.assertTrue(reply.status() == EditorNetwork.SNAPSHOT
                && !ConfigFile.parsePreferences(reply.json()).get("schematicRefill").getAsBoolean(),
                Component.literal("Only this connection's preferences are returned"));
        helper.assertTrue(EditorNetwork.query(first, new EditorNetwork.Query(2)).status() == EditorNetwork.QUERY_BUSY,
                Component.literal("Queries are rate limited independently of saves"));
        var other = EditorNetwork.query(second, new EditorNetwork.Query(1));
        helper.assertTrue(other.status() == EditorNetwork.SNAPSHOT && ConfigFile.parsePreferences(other.json()).isEmpty(),
                Component.literal("Another player receives only their own preferences"));
        helper.assertTrue(!store.read(first.getUUID()).get("schematicRefill").getAsBoolean(),
                Component.literal("Query does not mutate stored preferences"));
        helper.succeed();
    }

    @GameTest public void timedOutSaveMustStillBeReconcilable(GameTestHelper helper) throws Exception {
        var config = MagicShulkerBoxes.config();
        boolean oldPolicy = config.allowPlayerSettings;
        try {
            config.allowPlayerSettings = true;
            var player = player(helper);
            var session = new SettingsSession();
            session.connected(); session.policy(true);
            int request = session.beginSave(session.revision());
            var result = EditorNetwork.save(player, new EditorNetwork.Save(request, "{\"schematicRefill\":false}"));
            helper.assertTrue(result.status() == EditorNetwork.SAVED, Component.literal("Server commits save"));
            helper.assertTrue(!MagicShulkerBoxes.configFor(player).schematicRefill, Component.literal("Server refill is now off"));
            helper.assertTrue(session.timeout(request), Component.literal("Timeout keeps an outstanding save recoverable"));
            var snapshot = EditorNetwork.query(player, new EditorNetwork.Query(request));
            helper.assertTrue(snapshot.status() == EditorNetwork.SNAPSHOT
                    && !ConfigFile.parsePreferences(snapshot.json()).get("schematicRefill").getAsBoolean(),
                    Component.literal("Recovery query reads the committed server preference"));
            helper.assertTrue(session.acknowledgeResult(result.request(), result.status()), Component.literal("Late committed result must be reconcilable"));
            helper.assertTrue(!session.acknowledgeResult(snapshot.request(), snapshot.status()), Component.literal("A duplicate query reply cannot apply twice"));
            helper.succeed();
        } finally { config.allowPlayerSettings = oldPolicy; }
    }

    @GameTest public void measureBlockedRefillFallback(GameTestHelper helper) {
        var config = MagicShulkerBoxes.config();
        boolean oldFull = config.refillFullStack, oldMixed = config.allowMixedItemsWhenMakingSpace;
        try {
            config.refillFullStack = false;
            config.allowMixedItemsWhenMakingSpace = true;
            var beans = ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean bean
                    && bean.isThreadAllocatedMemorySupported() ? bean : null;
            if (beans != null && !beans.isThreadAllocatedMemoryEnabled()) beans.setThreadAllocatedMemoryEnabled(true);
            long thread = Thread.currentThread().threadId();
            for (int sample = 0; sample < 8; sample++) {
                var player = player(helper);
                var contents = new ArrayList<ItemStack>();
                for (int i = 0; i < 27; i++) contents.add(new ItemStack(Items.STONE, 64));
                for (int i = 0; i < 36; i++) player.getInventory().setItem(i,
                        i < 18 ? box(contents) : new ItemStack(Items.DIRT, 64));
                var request = request(helper, 0, 0, "minecraft:stone");
                long allocated = beans == null ? -1 : beans.getThreadAllocatedBytes(thread), start = System.nanoTime();
                int moved = RefillNetwork.accept(player, request);
                long duration = System.nanoTime() - start;
                long bytes = beans == null ? -1 : beans.getThreadAllocatedBytes(thread) - allocated;
                MagicShulkerBoxes.LOGGER.info("AUDIT blocked refill sample={} timeMs={} allocatedBytes={} moved={}",
                        sample, duration / 1_000_000.0, bytes, moved);
                helper.assertTrue(moved == 0, Component.literal("A single extracted item cannot free room for displaced dirt"));
                if (beans != null) helper.assertTrue(bytes < 4 * 1024 * 1024,
                        Component.literal("Equivalent failed plans must stay below 4 MiB; allocated=" + bytes));
            }
            helper.succeed();
        } finally {
            config.refillFullStack = oldFull; config.allowMixedItemsWhenMakingSpace = oldMixed;
        }
    }

    @GameTest public void differentSourceCountsRemainFallbackCandidates(GameTestHelper helper) {
        var player = player(helper);
        var inventory = player.getInventory();
        for (int i = 0; i < 36; i++) inventory.setItem(i, new ItemStack(Items.DIRT, 64));
        var contents = new ArrayList<ItemStack>();
        for (int i = 0; i < 27; i++) contents.add(new ItemStack(Items.STONE, i == 26 ? 1 : 64));
        inventory.setItem(35, box(contents));
        var config = MagicShulkerBoxes.config(); boolean old = config.refillFullStack;
        try {
            config.refillFullStack = false;
            helper.assertTrue(RefillNetwork.accept(player, request(helper, 35, 0, "minecraft:stone")) == 1,
                    Component.literal("A different count can free a slot after equivalent full stacks failed"));
            var stored = inventory.getItem(35).get(DataComponents.CONTAINER).stream().toList();
            helper.assertTrue(stored.get(26).is(Items.DIRT) && stored.get(26).getCount() == 64,
                    Component.literal("The displaced dirt occupies the one-item source's vacated slot"));
            helper.succeed();
        } finally { config.refillFullStack = old; }
    }

    private static ItemStack box(List<ItemStack> contents) {
        var box = new ItemStack(Items.SHULKER_BOX);
        box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(contents));
        return box;
    }
    @SuppressWarnings("removal") private static ServerPlayer player(GameTestHelper helper) {
        var player = helper.makeMockServerPlayerInLevel(); player.setGameMode(GameType.SURVIVAL); return player;
    }
    private static RefillNetwork.Request request(GameTestHelper helper, int boxSlot, int contentSlot, String item) {
        var stack = new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.parse(item)));
        return new RefillNetwork.Request(boxSlot, contentSlot, item, ItemFingerprint.of(stack, helper.getLevel().registryAccess()));
    }
}
