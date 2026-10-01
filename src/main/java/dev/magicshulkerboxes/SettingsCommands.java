package dev.magicshulkerboxes;

import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.server.players.NameAndId;

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
                .then(Commands.literal("set").then(settingArgument(context -> set(context.getSource(),
                        StringArgumentType.getString(context, "option"), StringArgumentType.getString(context, "value")))))
                .then(Commands.literal("reset").executes(context -> reset(context.getSource())))
                .then(Commands.literal("admin").requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_ADMIN))
                        .executes(context -> { reply(context.getSource(), "admin.help"); return 1; })
                        .then(playerCommands())
                        .then(Commands.literal("player-settings").then(Commands.argument("allowed", BoolArgumentType.bool())
                                .executes(context -> policy(context.getSource(), BoolArgumentType.getBool(context, "allowed")))))
                        .then(Commands.literal("reload").executes(context -> reload(context.getSource())))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> playerCommands() {
        return Commands.literal("player").then(Commands.argument("player", StringArgumentType.word())
                .suggests((context, builder) -> SharedSuggestionProvider.suggest(context.getSource().getOnlinePlayerNames(), builder))
                .then(Commands.literal("show").executes(context -> showPlayer(context.getSource(), target(context))))
                .then(Commands.literal("set").then(settingArgument(context -> editPlayer(context.getSource(), target(context),
                        StringArgumentType.getString(context, "option"), StringArgumentType.getString(context, "value")))))
                .then(Commands.literal("reset")
                        .executes(context -> editPlayer(context.getSource(), target(context), null, null))
                        .then(optionArgument().executes(context -> editPlayer(context.getSource(), target(context),
                                StringArgumentType.getString(context, "option"), null)))));
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> optionArgument() {
        return Commands.argument("option", StringArgumentType.word())
                .suggests((context, builder) -> SharedSuggestionProvider.suggest(ConfigFile.optionNames(), builder));
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> settingArgument(Command<CommandSourceStack> action) {
        return optionArgument().then(Commands.argument("value", StringArgumentType.word())
                .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                        StringArgumentType.getString(context, "option").equals("makeSpaceMode")
                                ? List.of("DISABLED", "MOVE_TO_BOX", "DROP_AND_PICKUP") : List.of("true", "false"), builder))
                .executes(action));
    }

    /** Resolve exactly one identity. UUIDs also work when an offline profile is no longer cached. */
    private static NameAndId target(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        var source = context.getSource();
        var input = StringArgumentType.getString(context, "player");
        var players = source.getServer().getPlayerList();
        var cache = source.getServer().services().nameToIdCache();
        try {
            var id = UUID.fromString(input);
            var online = players.getPlayer(id);
            return online == null ? cache.get(id).orElse(new NameAndId(id, id.toString())) : new NameAndId(online.getGameProfile());
        } catch (IllegalArgumentException ignored) {
            var online = players.getPlayerByName(input);
            return online == null ? cache.get(input).orElseThrow(GameProfileArgument.ERROR_UNKNOWN_PLAYER::create)
                    : new NameAndId(online.getGameProfile());
        }
    }

    private static int showPlayer(CommandSourceStack source, NameAndId target) {
        try {
            var config = MagicShulkerBoxes.config();
            var store = MagicShulkerBoxes.players(source.getServer());
            var overrides = store.read(target.id());
            var effective = ConfigFile.options(store.resolve(target.id(), config));
            reply(source, "admin.target", target.name(), target.id());
            reply(source, "policy", Messages.text(language(source), config.allowPlayerSettings ? "policy.personal" : "policy.server"));
            effective.entrySet().forEach(entry -> reply(source, "admin.setting",
                    Messages.text(language(source), "option." + entry.getKey()), entry.getKey(),
                    overrides.has(entry.getKey()) ? overrides.get(entry.getKey()).getAsString() : Messages.text(language(source), "admin.inherit"),
                    entry.getValue().getAsString(), Messages.text(language(source), config.canEdit(entry.getKey()) ? "admin.allowed" : "admin.locked")));
            return 1;
        } catch (IOException exception) {
            MagicShulkerBoxes.LOGGER.warn("Cannot read player settings / 无法读取玩家设置: {}", target.id(), exception);
            return failure(source, "admin.load_failed");
        }
    }

    private static int editPlayer(CommandSourceStack source, NameAndId target, String key, String value) {
        if (key != null && !ConfigFile.optionNames().contains(key)) return failure(source, "invalid");
        JsonElement change = null;
        if (value != null) {
            if (key.equals("makeSpaceMode")) {
                try { StorageConfig.MakeSpaceMode.valueOf(value); }
                catch (IllegalArgumentException exception) { return failure(source, "invalid"); }
                change = new JsonPrimitive(value);
            } else if (value.equals("true") || value.equals("false")) change = new JsonPrimitive(Boolean.parseBoolean(value));
            else return failure(source, "invalid");
        }
        try {
            var store = MagicShulkerBoxes.players(source.getServer());
            var overrides = store.read(target.id());
            if (key == null) overrides = new JsonObject();
            else if (value == null) overrides.remove(key);
            else overrides.add(key, change);
            // Administrators can manage dormant choices; resolve() still enforces the shared and per-field policies.
            store.save(target.id(), overrides);
            var online = source.getServer().getPlayerList().getPlayer(target.id());
            if (online != null) SettingsNetwork.acknowledge(online);
            if (value != null) reply(source, "admin.saved", target.name(), Messages.text(language(source), "option." + key), value,
                    Messages.text(language(source), MagicShulkerBoxes.config().canEdit(key) ? "admin.active" : "admin.dormant"));
            else if (key != null) reply(source, "admin.reset_option", target.name(), Messages.text(language(source), "option." + key));
            else reply(source, "admin.reset", target.name());
            return 1;
        } catch (IOException exception) {
            MagicShulkerBoxes.LOGGER.warn("Cannot update player settings / 无法修改玩家设置: {}", target.id(), exception);
            return failure(source, "save_failed");
        }
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
        var effective = source.getPlayer() == null ? MagicShulkerBoxes.config() : MagicShulkerBoxes.configFor(source.getPlayer());
        reply(source, "title");
        reply(source, "policy", Messages.text(language(source), MagicShulkerBoxes.config().allowPlayerSettings ? "policy.personal" : "policy.server"));
        if (!effective.pickupStorageEnabled) reply(source, "pickup_off");
        if (!effective.schematicRefill) reply(source, "refill_off");
        if (details) {
            ConfigFile.options(effective).entrySet().forEach(entry -> reply(source, "setting",
                    Messages.text(language(source), "option." + entry.getKey()), entry.getKey(), entry.getValue().getAsString()));
        } else reply(source, "help");
        return 1;
    }

    private static int set(CommandSourceStack source, String key, String value) throws CommandSyntaxException {
        var player = source.getPlayerOrException();
        if (!ConfigFile.optionNames().contains(key)) return failure(source, "invalid");
        if (!MagicShulkerBoxes.config().canEdit(key)) return failure(source, "locked");
        try {
            var store = MagicShulkerBoxes.players(source.getServer());
            var overrides = ConfigFile.editable(store.read(player.getUUID()), MagicShulkerBoxes.config().playerEditableSettings);
            if (key.equals("makeSpaceMode")) overrides.addProperty(key, value);
            else if (value.equals("true") || value.equals("false")) overrides.addProperty(key, Boolean.parseBoolean(value));
            else return failure(source, "invalid");
            ConfigFile.parsePreferences(overrides.toString());
            store.saveAllowed(player.getUUID(), overrides, MagicShulkerBoxes.config());
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
            MagicShulkerBoxes.players(source.getServer()).saveAllowed(player.getUUID(), new JsonObject(), MagicShulkerBoxes.config());
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
