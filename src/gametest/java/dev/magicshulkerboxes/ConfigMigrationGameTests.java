package dev.magicshulkerboxes;

import java.nio.file.Files;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.LevelResource;

public class ConfigMigrationGameTests {
    @GameTest @SuppressWarnings("removal")
    public void resetPreferencesAndServerDefaultsKeepRealPickupOnGround(GameTestHelper helper) throws Exception {
        var player = helper.makeMockServerPlayerInLevel(); player.setGameMode(GameType.SURVIVAL);
        var inventory = player.getInventory();
        for (int i = 0; i < 36; i++) inventory.setItem(i, new ItemStack(Items.DIRT, 64));
        var box = new ItemStack(Items.SHULKER_BOX);
        box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.STONE))));
        inventory.setItem(9, box);
        var path = helper.getLevel().getServer().getWorldPath(LevelResource.ROOT)
                .resolve("data/magic_shulker_boxes/players").resolve(player.getUUID() + ".json");
        Files.createDirectories(path.getParent());
        Files.writeString(path, "{\"enabled\":true,\"includeOffhand\":true}");
        var store = MagicShulkerBoxes.players(helper.getLevel().getServer());
        var config = MagicShulkerBoxes.config();
        boolean pickup = config.enabled, allowed = config.allowPlayerSettings;
        try {
            config.enabled = new ServerConfig().enabled;
            config.allowPlayerSettings = true;
            store.migrateExisting();
            helper.assertTrue(store.read(player.getUUID()).isEmpty(), Component.literal("Old personal overrides reset to inheritance"));
            helper.assertTrue(Files.exists(path.resolveSibling(path.getFileName() + ".pre-0.3.1.bak")), Component.literal("Old player file has a backup"));
            var dropped = new ItemEntity(helper.getLevel(), 0, 4, 0, new ItemStack(Items.STONE, 5));
            dropped.setNoPickUpDelay(); dropped.playerTouch(player);
            helper.assertTrue(!dropped.isRemoved() && dropped.getItem().getCount() == 5,
                    Component.literal("New defaults never automatically collect overflow"));
            config.enabled = true;
            dropped.playerTouch(player);
            helper.assertTrue(dropped.isRemoved(), Component.literal("Explicitly enabling storage still works after migration"));
            var result = EditorNetwork.save(player, new EditorNetwork.Save(1, "{\"enabled\":false}"));
            helper.assertTrue(result.status() == EditorNetwork.SAVED, Component.literal("New GUI protocol saves normally"));
            store.clearCache();
            helper.assertTrue(!store.read(player.getUUID()).get("enabled").getAsBoolean(),
                    Component.literal("New versioned preference survives reloading"));
            helper.succeed();
        } finally { config.enabled = pickup; config.allowPlayerSettings = allowed; }
    }

    @GameTest public void onlyCurrentSettingsReceiversAreRegistered(GameTestHelper helper) {
        var channels = ServerPlayNetworking.getGlobalReceivers();
        for (var id : List.of(SettingsNetwork.Preferences.ID.id(), EditorNetwork.Save.ID.id(), EditorNetwork.Query.ID.id())) {
            helper.assertTrue(channels.contains(id), Component.literal("Current settings channel is registered: " + id));
        }
        for (var old : List.of("preferences_v1", "editor_save_v1", "editor_query_v1")) {
            helper.assertTrue(!channels.contains(Identifier.fromNamespaceAndPath("magic_shulker_boxes", old)),
                    Component.literal("Old client cannot re-upload legacy settings: " + old));
        }
        helper.succeed();
    }
}
