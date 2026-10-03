package dev.magicshulkerboxes;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.level.storage.LevelResource;

public class AdminSettingsGameTests {
    @GameTest
    public void offlineTargetShowsStoredAndEffectiveValuesAndPersistsEdits(GameTestHelper h) throws Exception {
        var server = h.getLevel().getServer();
        var config = MagicShulkerBoxes.config();
        boolean oldAllow = config.allowPlayerSettings, oldIpn = config.ipnRefill;
        var oldFields = config.playerEditableSettings;
        var target = UUID.randomUUID();
        var other = UUID.randomUUID();
        var store = MagicShulkerBoxes.players(server);
        var output = new Output();
        var admin = server.createCommandSourceStack().withSource(output);
        try {
            config.allowPlayerSettings = true;
            config.ipnRefill = true;
            config.playerEditableSettings = List.of("pickupStorageEnabled", "makeSpaceMode");
            store.save(target, ConfigFile.parsePreferences("{\"ipnRefill\":false}"));
            store.save(other, ConfigFile.parsePreferences("{\"useMixedBoxes\":false}"));
            server.services().nameToIdCache().add(new NameAndId(target, "MSBAdminTarget"));

            check(h, execute(admin, "msb admin player MSBAdminTarget show") == 1, "Offline name is resolved");
            check(h, output.lines.stream().anyMatch(line -> line.contains("ipnRefill")
                    && line.contains("false") && line.contains("true") && line.contains("locked")),
                    "Show distinguishes a dormant saved choice from the locked effective value");
            check(h, output.lines.stream().anyMatch(line -> line.contains("useMixedBoxes") && line.contains("inherit")),
                    "Show identifies inherited options");
            check(h, execute(admin, "msb admin player " + target + " set pickupStorageEnabled true") == 1,
                    "Console edits an offline UUID");
            check(h, execute(admin, "msb admin player " + target + " set makeSpaceMode DISABLED") == 1,
                    "Administrator can set enum options");
            store.clearCache();
            check(h, store.resolve(target, config).pickupStorageEnabled
                    && store.resolve(target, config).makeSpaceMode == StorageConfig.MakeSpaceMode.DISABLED,
                    "Changes are active and survive cache reload");
            check(h, store.read(target).has("ipnRefill") && !store.read(other).get("useMixedBoxes").getAsBoolean(),
                    "Edits preserve unrelated options and other players");
            check(h, execute(admin, "msb admin player " + target + " reset pickupStorageEnabled") == 1,
                    "Individual reset succeeds");
            check(h, !store.read(target).has("pickupStorageEnabled") && store.read(target).has("makeSpaceMode"),
                    "Individual reset restores inheritance without removing another override");
            check(h, execute(admin, "msb admin player " + target + " reset") == 1, "Full reset succeeds");
            store.clearCache();
            check(h, store.read(target).isEmpty(), "Administrator reset clears dormant locked choices too");
        } finally {
            config.allowPlayerSettings = oldAllow;
            config.ipnRefill = oldIpn;
            config.playerEditableSettings = oldFields;
        }
        h.succeed();
    }

    @GameTest
    public void administratorEditsLockedChoicesWithoutBypassingServerPolicy(GameTestHelper h) throws Exception {
        var server = h.getLevel().getServer();
        var config = MagicShulkerBoxes.config();
        boolean oldAllow = config.allowPlayerSettings, oldPickup = config.pickupStorageEnabled;
        var oldFields = config.playerEditableSettings;
        var target = UUID.randomUUID();
        var store = MagicShulkerBoxes.players(server);
        var admin = server.createCommandSourceStack().withPermission(LevelBasedPermissionSet.ADMIN);
        try {
            config.allowPlayerSettings = false;
            config.pickupStorageEnabled = false;
            config.playerEditableSettings = List.of();
            check(h, execute(admin, "msb admin player " + target + " set pickupStorageEnabled true") == 1,
                    "Administrator may prepare personal choices while policy is off");
            check(h, store.read(target).get("pickupStorageEnabled").getAsBoolean()
                    && !store.resolve(target, config).pickupStorageEnabled, "Master policy still enforces server defaults");
            config.allowPlayerSettings = true;
            check(h, !store.resolve(target, config).pickupStorageEnabled, "Field policy still enforces server defaults");
            config.playerEditableSettings = List.of("pickupStorageEnabled");
            check(h, store.resolve(target, config).pickupStorageEnabled, "Granting permission activates the saved choice");
            config.playerEditableSettings = List.of();
            check(h, execute(admin, "msb admin player " + target + " reset pickupStorageEnabled") == 1,
                    "Administrator can reset a locked choice");
            check(h, store.read(target).isEmpty(), "Locked choice was removed");
        } finally {
            config.allowPlayerSettings = oldAllow;
            config.pickupStorageEnabled = oldPickup;
            config.playerEditableSettings = oldFields;
        }
        h.succeed();
    }

    @GameTest
    public void insufficientPermissionsHideAndRejectEveryAdministratorOperation(GameTestHelper h) throws Exception {
        var server = h.getLevel().getServer();
        var target = UUID.randomUUID();
        var store = MagicShulkerBoxes.players(server);
        store.save(target, ConfigFile.parsePreferences("{\"pickupStorageEnabled\":true}"));
        var before = store.read(target);
        var dispatcher = server.getCommands().getDispatcher();
        for (var permission : List.of(LevelBasedPermissionSet.ALL, LevelBasedPermissionSet.GAMEMASTER)) {
            var source = server.createCommandSourceStack().withPermission(permission);
            for (var suffix : List.of("show", "set pickupStorageEnabled false", "reset", "reset pickupStorageEnabled")) {
                try {
                    execute(source, "msb admin player " + target + " " + suffix);
                    throw new AssertionError("Unauthorized command accepted: " + suffix);
                } catch (CommandSyntaxException expected) { }
            }
            check(h, !dispatcher.getRoot().getChild("msb").getChild("admin").canUse(source),
                    "Unauthorized administrator branch is excluded from the usable command tree");
        }
        check(h, store.read(target).equals(before), "Rejected commands preserve the target settings");
        h.succeed();
    }

    @GameTest
    public void invalidValuesAndUnreadableFilesFailWithoutChangingStoredSettings(GameTestHelper h) throws Exception {
        var server = h.getLevel().getServer();
        var admin = server.createCommandSourceStack();
        var target = UUID.randomUUID();
        var store = MagicShulkerBoxes.players(server);
        store.save(target, ConfigFile.parsePreferences("{\"pickupStorageEnabled\":false,\"ipnRefill\":true}"));
        var path = server.getWorldPath(LevelResource.ROOT).resolve("data/magic_shulker_boxes/players/" + target + ".json");
        var before = Files.readString(path);
        for (var suffix : List.of("set pickupStorageEnabled yes", "set makeSpaceMode WRONG",
                "set allowPlayerSettings true", "set unknown false", "reset unknown")) {
            check(h, execute(admin, "msb admin player " + target + " " + suffix) == 0, "Invalid command fails: " + suffix);
            check(h, Files.readString(path).equals(before), "Invalid input cannot write the file");
        }
        Files.writeString(path, "{broken");
        store.clearCache();
        check(h, execute(admin, "msb admin player " + target + " show") == 0, "Show reports load failure");
        check(h, execute(admin, "msb admin player " + target + " set ipnRefill false") == 0, "Edit reports load failure");
        check(h, execute(admin, "msb admin player " + target + " reset") == 0, "Reset cannot discard a corrupt file");
        check(h, Files.readString(path).equals("{broken"), "Original invalid file is preserved");
        Files.delete(path);
        store.clearCache();
        h.succeed();
    }

    private static int execute(CommandSourceStack source, String command) throws CommandSyntaxException {
        return source.getServer().getCommands().getDispatcher().execute(command, source);
    }

    private static void check(GameTestHelper h, boolean ok, String message) {
        h.assertTrue(ok, Component.literal(message));
    }

    private static final class Output implements CommandSource {
        final List<String> lines = new ArrayList<>();
        @Override public void sendSystemMessage(Component message) { lines.add(message.getString()); }
        @Override public boolean acceptsSuccess() { return true; }
        @Override public boolean acceptsFailure() { return true; }
        @Override public boolean shouldInformAdmins() { return false; }
    }
}
