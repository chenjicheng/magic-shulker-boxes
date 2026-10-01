package dev.magicshulkerboxes;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.permissions.LevelBasedPermissionSet;

public class CommandChatGameTests {
    @GameTest
    public void administratorChoicesPreviewEveryValueForTheExactUuidWithoutSaving(GameTestHelper h) throws Exception {
        var server = h.getLevel().getServer();
        var target = UUID.randomUUID();
        var store = MagicShulkerBoxes.players(server);
        store.save(target, ConfigFile.parsePreferences("{\"ipnRefill\":false}"));
        var before = store.read(target);
        var output = new Output();
        var source = server.createCommandSourceStack().withSource(output);
        execute(source, "msb admin player " + target + " show");
        var commands = output.commands();
        for (var key : ConfigFile.optionNames()) {
            for (var value : values(key)) check(h, commands.contains("/msb admin player " + target + " set " + key + " " + value),
                    "Every administrator option has a complete value suggestion: " + key);
            check(h, commands.contains("/msb admin player " + target + " reset " + key), "Inheritance binds the same UUID");
        }
        check(h, store.read(target).equals(before), "Displaying command choices never changes settings");
        checkPreviewStyles(h, output);
        h.succeed();
    }

    @GameTest @SuppressWarnings("removal")
    public void personalChoicesRespectLocksAndIndividualResetPreservesOtherChoices(GameTestHelper h) throws Exception {
        var config = MagicShulkerBoxes.config();
        boolean oldAllow = config.allowPlayerSettings;
        var oldFields = config.playerEditableSettings;
        var player = h.makeMockServerPlayerInLevel();
        var store = MagicShulkerBoxes.players(h.getLevel().getServer());
        var output = new Output();
        var source = player.createCommandSourceStack().withSource(output).withPermission(LevelBasedPermissionSet.ALL);
        try {
            config.allowPlayerSettings = true;
            config.playerEditableSettings = List.of("ipnRefill");
            store.save(player.getUUID(), ConfigFile.parsePreferences("{\"ipnRefill\":false,\"pickupStorageEnabled\":true}"));
            execute(source, "msb show");
            check(h, output.commands().containsAll(List.of("/msb set ipnRefill true", "/msb set ipnRefill false", "/msb reset ipnRefill")),
                    "Personal permitted option has actionable choices");
            check(h, output.commands().stream().noneMatch(command -> command.contains("pickupStorageEnabled")),
                    "Locked personal options have no modification buttons");
            check(h, execute(source, "msb reset ipnRefill") == 1, "Personal individual reset is available");
            check(h, !store.read(player.getUUID()).has("ipnRefill") && store.read(player.getUUID()).has("pickupStorageEnabled"),
                    "Personal reset preserves another dormant choice");
            check(h, execute(source, "msb reset pickupStorageEnabled") == 0, "Individual reset cannot edit a locked option");
            output.components.clear();
            config.allowPlayerSettings = false;
            execute(source, "msb show");
            check(h, output.commands().isEmpty(), "Policy off hides all personal modification choices");
        } finally {
            config.allowPlayerSettings = oldAllow;
            config.playerEditableSettings = oldFields;
        }
        h.succeed();
    }

    @GameTest
    public void serverChatCoversEveryDefaultAndEveryPermissionWithoutChangingConfig(GameTestHelper h) throws Exception {
        var server = h.getLevel().getServer();
        var output = new Output();
        var source = server.createCommandSourceStack().withSource(output);
        var before = ConfigFile.json(MagicShulkerBoxes.config());
        execute(source, "msb admin show");
        var commands = output.commands();
        for (var key : ConfigFile.optionNames()) {
            for (var value : values(key)) check(h, commands.contains("/msb admin set " + key + " " + value),
                    "Server default has every value choice: " + key);
            check(h, commands.contains("/msb admin reset " + key), "Server default can reset individually");
            for (var allowed : List.of("true", "false")) check(h, commands.contains("/msb admin permission " + key + " " + allowed),
                    "Every field permission can be previewed");
        }
        check(h, commands.containsAll(List.of("/msb admin set allowPlayerSettings true", "/msb admin set allowPlayerSettings false",
                "/msb admin reset allowPlayerSettings", "/msb admin permissions all", "/msb admin permissions none",
                "/msb admin reset playerEditableSettings")), "Both server policy settings are interactive");
        check(h, commands.stream().allMatch(command -> command.length() <= 256), "Buttons fit vanilla chat command length");
        check(h, ConfigFile.json(MagicShulkerBoxes.config()).equals(before), "Viewing server settings is read-only");
        checkPreviewStyles(h, output);
        h.succeed();
    }

    @GameTest
    public void serverEditsPersistValidateAndRequireAdministratorPermission(GameTestHelper h) throws Exception {
        var server = h.getLevel().getServer();
        var original = ConfigFile.parseServer(ConfigFile.json(MagicShulkerBoxes.config()));
        var source = server.createCommandSourceStack();
        var configPath = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("magic_shulker_boxes.json");
        try {
            check(h, execute(source, "msb admin set pickupStorageEnabled true") == 1, "Server boolean edit succeeds");
            check(h, execute(source, "msb admin set makeSpaceMode DISABLED") == 1, "Server enum edit succeeds");
            check(h, execute(source, "msb admin set allowPlayerSettings true") == 1, "Master permission is editable");
            check(h, execute(source, "msb admin permissions none") == 1, "All permissions can be locked");
            check(h, execute(source, "msb admin permission ipnRefill true") == 1, "Individual permission can be granted");
            var saved = ConfigFile.load(configPath);
            check(h, saved.pickupStorageEnabled && saved.makeSpaceMode == StorageConfig.MakeSpaceMode.DISABLED
                    && saved.allowPlayerSettings && saved.playerEditableSettings.equals(List.of("ipnRefill")), "Changes persist to the server file");
            check(h, execute(source, "msb admin reset pickupStorageEnabled") == 1, "Single default reset succeeds");
            check(h, !MagicShulkerBoxes.config().pickupStorageEnabled
                    && MagicShulkerBoxes.config().makeSpaceMode == StorageConfig.MakeSpaceMode.DISABLED, "Reset preserves another server setting");
            check(h, execute(source, "msb admin permissions all") == 1, "All field permissions can be restored");
            check(h, MagicShulkerBoxes.config().playerEditableSettings.containsAll(ConfigFile.optionNames()), "Every field is included");
            var before = ConfigFile.json(MagicShulkerBoxes.config());
            for (var command : List.of("set unknown true", "set ipnRefill yes", "set makeSpaceMode BAD",
                    "set playerEditableSettings [\"unknown\"]", "reset unknown", "permission unknown true")) {
                check(h, execute(source, "msb admin " + command) == 0, "Invalid server edit is rejected: " + command);
                check(h, ConfigFile.json(MagicShulkerBoxes.config()).equals(before), "Invalid edit preserves active settings");
            }
            var restricted = source.withPermission(LevelBasedPermissionSet.GAMEMASTER);
            for (var command : List.of("show", "set ipnRefill true", "reset ipnRefill", "permission ipnRefill true", "permissions none")) {
                try { execute(restricted, "msb admin " + command); throw new AssertionError("OP2 accessed server settings"); }
                catch (CommandSyntaxException expected) { }
            }
        } finally { MagicShulkerBoxes.replaceConfig(original); }
        h.succeed();
    }

    private static List<String> values(String key) {
        return key.equals("makeSpaceMode") ? List.of("DISABLED", "MOVE_TO_BOX", "DROP_AND_PICKUP") : List.of("true", "false");
    }
    private static Stream<Component> flatten(Component component) {
        return Stream.concat(Stream.of(component), component.getSiblings().stream().flatMap(CommandChatGameTests::flatten));
    }
    private static void checkPreviewStyles(GameTestHelper h, Output output) {
        output.components.stream().flatMap(CommandChatGameTests::flatten).forEach(component -> {
            var click = component.getStyle().getClickEvent();
            if (click == null) return;
            check(h, click instanceof ClickEvent.SuggestCommand, "Clicks only suggest commands; never run them");
            check(h, component.getStyle().getHoverEvent() instanceof HoverEvent.ShowText hover
                    && hover.value().getString().contains(((ClickEvent.SuggestCommand) click).command()), "Hover shows the full command");
        });
    }
    private static int execute(CommandSourceStack source, String command) throws CommandSyntaxException {
        return source.getServer().getCommands().getDispatcher().execute(command, source);
    }
    private static void check(GameTestHelper h, boolean ok, String message) { h.assertTrue(ok, Component.literal(message)); }
    private static final class Output implements CommandSource {
        final List<Component> components = new ArrayList<>();
        List<String> commands() { return components.stream().flatMap(CommandChatGameTests::flatten)
                .map(component -> component.getStyle().getClickEvent()).filter(ClickEvent.SuggestCommand.class::isInstance)
                .map(ClickEvent.SuggestCommand.class::cast).map(ClickEvent.SuggestCommand::command).toList(); }
        @Override public void sendSystemMessage(Component message) { components.add(message); }
        @Override public boolean acceptsSuccess() { return true; }
        @Override public boolean acceptsFailure() { return true; }
        @Override public boolean shouldInformAdmins() { return false; }
    }
}
