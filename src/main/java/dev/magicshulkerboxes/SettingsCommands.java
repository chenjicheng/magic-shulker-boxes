package dev.magicshulkerboxes;

import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.io.IOException;
import java.util.List;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.server.permissions.Permissions;

public final class SettingsCommands {
    private SettingsCommands() {}

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) -> register(dispatcher));
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("msb")
                .executes(context -> show(context.getSource(), false))
                .then(Commands.literal("help").executes(context -> show(context.getSource(), false)))
                .then(Commands.literal("show").executes(context -> show(context.getSource(), true)))
                .then(Commands.literal("set").then(Commands.argument("option", StringArgumentType.word())
                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(ConfigFile.optionNames(), builder))
                        .then(Commands.argument("value", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                        StringArgumentType.getString(context, "option").equals("makeSpaceMode")
                                                ? List.of("DISABLED", "MOVE_TO_BOX", "DROP_AND_PICKUP") : List.of("true", "false"), builder))
                                .executes(context -> set(context.getSource(), StringArgumentType.getString(context, "option"), StringArgumentType.getString(context, "value"))))))
                .then(Commands.literal("reset").executes(context -> reset(context.getSource())))
                .then(Commands.literal("admin").requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_ADMIN))
                        .then(Commands.literal("player-settings").then(Commands.argument("allowed", BoolArgumentType.bool())
                                .executes(context -> policy(context.getSource(), BoolArgumentType.getBool(context, "allowed")))))
                        .then(Commands.literal("reload").executes(context -> reload(context.getSource())))));
    }

    private static String language(CommandSourceStack source) {
        return source.getPlayer() == null ? "en_us" : source.getPlayer().clientInformation().language();
    }

    private static void reply(CommandSourceStack source, String key, Object... arguments) {
        source.sendSuccess(() -> Messages.text(language(source), key, arguments), false);
    }

    private static int failure(CommandSourceStack source, String key) {
        source.sendFailure(Messages.text(language(source), key));
        return 0;
    }

    private static int show(CommandSourceStack source, boolean details) {
        reply(source, "title");
        reply(source, "policy", Messages.text(language(source), MagicShulkerBoxes.config().allowPlayerSettings ? "policy.personal" : "policy.server"));
        if (!MagicShulkerBoxes.config().enabled) reply(source, "global_off");
        if (details) {
            var effective = source.getPlayer() == null ? MagicShulkerBoxes.config() : MagicShulkerBoxes.configFor(source.getPlayer());
            ConfigFile.options(effective).entrySet().forEach(entry -> reply(source, "setting",
                    Messages.text(language(source), "option." + entry.getKey()), entry.getKey(), entry.getValue().getAsString()));
        } else reply(source, "help");
        return 1;
    }

    private static int set(CommandSourceStack source, String key, String value) throws CommandSyntaxException {
        var player = source.getPlayerOrException();
        if (!MagicShulkerBoxes.config().allowPlayerSettings) return failure(source, "locked");
        if (!ConfigFile.optionNames().contains(key)) return failure(source, "invalid");
        try {
            var store = MagicShulkerBoxes.players(source.getServer());
            var overrides = store.read(player.getUUID());
            if (key.equals("makeSpaceMode")) overrides.addProperty(key, value);
            else if (value.equals("true") || value.equals("false")) overrides.addProperty(key, Boolean.parseBoolean(value));
            else return failure(source, "invalid");
            ConfigFile.parsePreferences(overrides.toString());
            store.save(player.getUUID(), overrides);
            SettingsNetwork.acknowledge(player);
            reply(source, "saved", Messages.text(language(source), "option." + key), value);
            return 1;
        } catch (IOException exception) {
            MagicShulkerBoxes.LOGGER.warn("Cannot update player settings / 无法修改玩家设置", exception);
            return failure(source, "save_failed");
        }
    }

    private static int reset(CommandSourceStack source) throws CommandSyntaxException {
        var player = source.getPlayerOrException();
        if (!MagicShulkerBoxes.config().allowPlayerSettings) return failure(source, "locked");
        try {
            MagicShulkerBoxes.players(source.getServer()).save(player.getUUID(), new JsonObject());
            SettingsNetwork.acknowledge(player);
            reply(source, "reset");
            return 1;
        } catch (IOException exception) { return failure(source, "save_failed"); }
    }

    private static int policy(CommandSourceStack source, boolean allow) {
        try {
            MagicShulkerBoxes.allowPlayerSettings(allow);
            SettingsNetwork.broadcastPolicy(source.getServer());
            reply(source, "policy", Messages.text(language(source), allow ? "policy.personal" : "policy.server"));
            return 1;
        } catch (IOException exception) { return failure(source, "save_failed"); }
    }

    private static int reload(CommandSourceStack source) {
        try {
            MagicShulkerBoxes.reload();
            SettingsNetwork.broadcastPolicy(source.getServer());
            reply(source, "reloaded");
            return 1;
        } catch (IOException exception) { return failure(source, "reload_failed"); }
    }
}
