package dev.magicshulkerboxes;

import com.google.gson.JsonObject;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.GameType;

public class RefillAmountGameTests {
    @GameTest public void personalQuantityControlsRealNetworkExtraction(GameTestHelper helper) throws Exception {
        var config = MagicShulkerBoxes.config();
        boolean oldPolicy = config.allowPlayerSettings, oldRefill = config.schematicRefill;
        var oldFields = config.playerEditableSettings;
        int oldAmount = config.refillAmount;
        try {
            config.allowPlayerSettings = true;
            config.schematicRefill = true;
            config.refillAmount = 64;
            config.playerEditableSettings = List.of("refillAmount");
            var player = helper.makeMockServerPlayerInLevel();
            player.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
            player.getInventory().clearContent();
            var source = new ItemStack(Items.STONE, 64);
            var box = new ItemStack(Items.SHULKER_BOX);
            box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(source)));
            player.getInventory().setItem(9, box);
            var choice = new JsonObject(); choice.addProperty("refillAmount", 17);
            MagicShulkerBoxes.players(player.level().getServer()).saveAllowed(player.getUUID(), choice, config);
            int moved = RefillNetwork.accept(player, new RefillNetwork.Request(9, 0, "minecraft:stone",
                    ItemFingerprint.of(source, player.registryAccess())));
            helper.assertTrue(moved == 17 && player.getInventory().getItem(0).getCount() == 17,
                    Component.literal("Server applies the player's quantity to an actual refill request"));
            helper.assertTrue(player.getInventory().getItem(9).get(DataComponents.CONTAINER).stream()
                    .mapToInt(ItemStack::getCount).sum() == 47, Component.literal("Source retains exactly 47 stone"));
            config.playerEditableSettings = List.of();
            helper.assertTrue(MagicShulkerBoxes.configFor(player).refillAmount == 64,
                    Component.literal("Revocation restores the server's quantity"));
            helper.succeed();
        } finally {
            config.allowPlayerSettings = oldPolicy; config.schematicRefill = oldRefill;
            config.playerEditableSettings = oldFields; config.refillAmount = oldAmount;
        }
    }

    @GameTest public void commandsAcceptArbitraryWholeQuantitiesAndRejectInvalidValues(GameTestHelper helper) throws Exception {
        var original = ConfigFile.parseServer(ConfigFile.json(MagicShulkerBoxes.config()));
        var server = helper.getLevel().getServer();
        var admin = server.createCommandSourceStack().withPermission(LevelBasedPermissionSet.ADMIN);
        var dispatcher = server.getCommands().getDispatcher();
        try {
            helper.assertTrue(dispatcher.execute("msb admin set refillAmount 23", admin) == 1
                    && MagicShulkerBoxes.config().refillAmount == 23, Component.literal("Administrator can set an arbitrary quantity"));
            for (String invalid : List.of("0", "65", "1.5", "true", "-1")) {
                helper.assertTrue(dispatcher.execute("msb admin set refillAmount " + invalid, admin) == 0
                        && MagicShulkerBoxes.config().refillAmount == 23, Component.literal("Invalid quantity preserves 23: " + invalid));
            }
            var player = helper.makeMockServerPlayerInLevel();
            MagicShulkerBoxes.config().allowPlayerSettings = true;
            MagicShulkerBoxes.config().playerEditableSettings = List.of("refillAmount");
            helper.assertTrue(dispatcher.execute("msb set refillAmount 7", player.createCommandSourceStack()) == 1
                    && MagicShulkerBoxes.configFor(player).refillAmount == 7, Component.literal("Personal command applies quantity 7"));
            helper.assertTrue(dispatcher.execute("msb reset refillAmount", player.createCommandSourceStack()) == 1
                    && MagicShulkerBoxes.configFor(player).refillAmount == 23, Component.literal("Reset inherits quantity 23"));
            helper.succeed();
        } finally { MagicShulkerBoxes.replaceConfig(original); }
    }
}
