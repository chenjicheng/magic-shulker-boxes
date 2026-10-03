package dev.magicshulkerboxes;

import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.GameType;

public class SettingPermissionsGameTests {
    @GameTest
    @SuppressWarnings("removal")
    public void networkGuiCommandsAndRevocationShareFieldPolicy(GameTestHelper h) throws Exception {
        var c = MagicShulkerBoxes.config();
        boolean oldAllow = c.allowPlayerSettings,
                oldPickup = c.pickupStorageEnabled,
                oldIpn = c.ipnRefill;
        var oldFields = c.playerEditableSettings;
        try {
            c.allowPlayerSettings = true;
            c.pickupStorageEnabled = false;
            c.ipnRefill = true;
            c.playerEditableSettings = List.of("ipnRefill");
            var p = h.makeMockServerPlayerInLevel();
            p.setGameMode(GameType.SURVIVAL);
            var store = MagicShulkerBoxes.players(h.getLevel().getServer());
            store.save(p.getUUID(), ConfigFile.parsePreferences("{\"pickupStorageEnabled\":true}"));
            check(
                    h,
                    !MagicShulkerBoxes.configFor(p).pickupStorageEnabled,
                    "Existing personal file cannot bypass a locked field");
            try {
                SettingsNetwork.accept(p, "{\"pickupStorageEnabled\":true}");
                throw new AssertionError("Locked sync accepted");
            } catch (java.io.IOException expected) {
            }
            check(
                    h,
                    EditorNetwork.save(
                                            p,
                                            new EditorNetwork.Save(
                                                    1, "{\"pickupStorageEnabled\":true}"))
                                    .status()
                            == EditorNetwork.LOCKED,
                    "GUI cannot save a locked option");
            var guiPlayer = h.makeMockServerPlayerInLevel();
            guiPlayer.setGameMode(GameType.SURVIVAL);
            check(
                    h,
                    EditorNetwork.save(
                                                    guiPlayer,
                                                    new EditorNetwork.Save(
                                                            1, "{\"ipnRefill\":false}"))
                                            .status()
                                    == EditorNetwork.SAVED
                            && !MagicShulkerBoxes.configFor(guiPlayer).ipnRefill,
                    "GUI accepts permitted field on the authenticated player only");
            h.getLevel()
                    .getServer()
                    .getCommands()
                    .performPrefixedCommand(
                            p.createCommandSourceStack(), "msb set pickupStorageEnabled true");
            check(
                    h,
                    !MagicShulkerBoxes.configFor(p).pickupStorageEnabled,
                    "Player command cannot alter a locked option");
            check(
                    h,
                    SettingsNetwork.accept(p, "{\"ipnRefill\":false}"),
                    "Allowed field sync accepted");
            check(
                    h,
                    !MagicShulkerBoxes.configFor(p).ipnRefill
                            && store.read(p.getUUID()).has("pickupStorageEnabled"),
                    "Legal partial override preserves dormant choice");
            c.playerEditableSettings = List.of("pickupStorageEnabled");
            check(
                    h,
                    MagicShulkerBoxes.configFor(p).pickupStorageEnabled
                            && MagicShulkerBoxes.configFor(p).ipnRefill,
                    "Runtime policy swap immediately changes effective values");
        } finally {
            c.allowPlayerSettings = oldAllow;
            c.pickupStorageEnabled = oldPickup;
            c.ipnRefill = oldIpn;
            c.playerEditableSettings = oldFields;
        }
        h.succeed();
    }

    private static void check(GameTestHelper h, boolean ok, String message) {
        h.assertTrue(ok, Component.literal(message));
    }
}
