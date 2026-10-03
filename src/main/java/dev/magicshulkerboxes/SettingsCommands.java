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
import net.minecraft.network.chat.Component;
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
                .then(Commands.literal("show").executes(context -> show(context.getSource(), true))
                        .then(optionArgument().executes(context -> show(context.getSource(), true, StringArgumentType.getString(context, "option")))))
                .then(Commands.literal("set").then(settingArgument(context -> set(context.getSource(),
                        StringArgumentType.getString(context, "option"), StringArgumentType.getString(context, "value")))))
                .then(Commands.literal("reset").executes(context -> reset(context.getSource()))
                        .then(optionArgument().executes(context -> reset(context.getSource(), StringArgumentType.getString(context, "option")))))
                .then(adminCommands()));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> adminCommands() {
        return Commands.literal("admin").requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_ADMIN))
                .executes(context -> showServer(context.getSource(), null))
                .then(Commands.literal("show").executes(context -> showServer(context.getSource(), null))
                        .then(serverOptionArgument().executes(context -> showServer(context.getSource(), StringArgumentType.getString(context, "option")))))
                .then(Commands.literal("set").then(serverOptionArgument()
                        .then(Commands.argument("value", StringArgumentType.greedyString())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                        StringArgumentType.getString(context, "option").equals("makeSpaceMode")
                                                ? List.of("DISABLED", "MOVE_TO_BOX", "DROP_AND_PICKUP")
                                                : StringArgumentType.getString(context, "option").equals("playerEditableSettings") ? List.of("[]") : List.of("true", "false"), builder))
                                .executes(context -> editServer(context.getSource(), StringArgumentType.getString(context, "option"), StringArgumentType.getString(context, "value"))))))
                .then(Commands.literal("reset").then(serverOptionArgument().executes(context -> editServer(context.getSource(), StringArgumentType.getString(context, "option"), null))))
                .then(Commands.literal("permission").then(optionArgument().then(Commands.argument("allowed", BoolArgumentType.bool())
                        .executes(context -> permission(context.getSource(), StringArgumentType.getString(context, "option"), BoolArgumentType.getBool(context, "allowed"))))))
                .then(Commands.literal("permissions")
                        .then(Commands.literal("all").executes(context -> editServer(context.getSource(), "playerEditableSettings", null)))
                        .then(Commands.literal("none").executes(context -> editServer(context.getSource(), "playerEditableSettings", "[]"))))
                .then(playerCommands())
                .then(Commands.literal("player-settings").then(Commands.argument("allowed", BoolArgumentType.bool())
                        .executes(context -> policy(context.getSource(), BoolArgumentType.getBool(context, "allowed")))))
                .then(Commands.literal("reload").executes(context -> reload(context.getSource())));
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> serverOptionArgument() {
        return Commands.argument("option", StringArgumentType.word())
                .suggests((context, builder) -> SharedSuggestionProvider.suggest(ServerSettingsEdit.optionNames(), builder));
    }

    private static int showServer(CommandSourceStack source, String only) {
        if (only != null && !ServerSettingsEdit.optionNames().contains(only)) return failure(source, "invalid");
        var config = MagicShulkerBoxes.config();
        reply(source, "server.title");
        reply(source, "command.hint");
        var options = ConfigFile.options(config);
        options.addProperty("allowPlayerSettings", config.allowPlayerSettings);
        options.entrySet().stream().filter(entry -> only == null || only.equals(entry.getKey())).forEach(entry -> {
            var key = entry.getKey();
            var row = Messages.text(language(source), "setting", Messages.text(language(source), "option." + key), key, entry.getValue().getAsString()).copy();
            row.append(SettingsChat.choices(language(source), key, "/msb admin set", "/msb admin reset", "command.default"));
            if (ConfigFile.optionNames().contains(key)) {
                row.append(" ").append(SettingsChat.preview(language(source), Messages.text(language(source), "command.allow"), "/msb admin permission " + key + " true"));
                row.append(" ").append(SettingsChat.preview(language(source), Messages.text(language(source), "command.lock"), "/msb admin permission " + key + " false"));
                row.append(" ").append(Messages.text(language(source), config.playerEditableSettings.contains(key) ? "server.field_allowed" : "server.field_locked"));
            }
            reply(source, row);
        });
        if (only == null || only.equals("playerEditableSettings")) {
            var row = Messages.text(language(source), "server.permissions", config.playerEditableSettings.toString()).copy();
            row.append(" ").append(SettingsChat.preview(language(source), Messages.text(language(source), "command.all"), "/msb admin permissions all"));
            row.append(" ").append(SettingsChat.preview(language(source), Messages.text(language(source), "command.none"), "/msb admin permissions none"));
            row.append(" ").append(SettingsChat.preview(language(source), Messages.text(language(source), "command.default"), "/msb admin reset playerEditableSettings"));
            reply(source, row);
        }
        return 1;
    }

    private static int editServer(CommandSourceStack source, String key, String value) {
        final ServerConfig replacement;
        try { replacement = ServerSettingsEdit.edit(MagicShulkerBoxes.config(), key, value == null ? null : value.trim()); }
        catch (IOException exception) { return failure(source, "invalid"); }
        return saveServer(source, replacement, key);
    }

    private static int permission(CommandSourceStack source, String option, boolean allowed) {
        final ServerConfig replacement;
        try { replacement = ServerSettingsEdit.permission(MagicShulkerBoxes.config(), option, allowed); }
        catch (IOException exception) { return failure(source, "invalid"); }
        return saveServer(source, replacement, "playerEditableSettings");
    }

    private static int saveServer(CommandSourceStack source, ServerConfig replacement, String key) {
        try {
            MagicShulkerBoxes.replaceConfig(replacement);
            SettingsNetwork.broadcastPolicy(source.getServer());
            reply(source, "server.saved", key);
            return 1;
        } catch (IOException exception) {
            MagicShulkerBoxes.LOGGER.warn("Cannot save server settings / 无法保存服务器设置", exception);
            return failure(source, "save_failed");
        }
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
            reply(source, "command.hint");
            effective.entrySet().forEach(entry -> {
                var row = Messages.text(language(source), "admin.setting",
                    Messages.text(language(source), "option." + entry.getKey()), entry.getKey(),
                    overrides.has(entry.getKey()) ? overrides.get(entry.getKey()).getAsString() : Messages.text(language(source), "admin.inherit"),
                    entry.getValue().getAsString(), Messages.text(language(source), config.canEdit(entry.getKey()) ? "admin.allowed" : "admin.locked")).copy();
                row.append(SettingsChat.choices(language(source), entry.getKey(), "/msb admin player " + target.id() + " set",
                        "/msb admin player " + target.id() + " reset", "command.inherit"));
                reply(source, row);
            });
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

    private static void reply(CommandSourceStack source, Component message) { source.sendSuccess(() -> message, false); }

    private static int failure(CommandSourceStack source, String key) {
        source.sendFailure(Messages.text(language(source), key));
        return 0;
    }

    private static int show(CommandSourceStack source, boolean details) {
        return show(source, details, null);
    }

    private static int show(CommandSourceStack source, boolean details, String only) {
        if (only != null && !ConfigFile.optionNames().contains(only)) return failure(source, "invalid");
        var effective = source.getPlayer() == null ? MagicShulkerBoxes.config() : MagicShulkerBoxes.configFor(source.getPlayer());
        reply(source, "title");
        reply(source, "policy", Messages.text(language(source), MagicShulkerBoxes.config().allowPlayerSettings ? "policy.personal" : "policy.server"));
        if (!effective.pickupStorageEnabled) reply(source, "pickup_off");
        if (!effective.schematicRefill) reply(source, "refill_off");
        if (details) {
            reply(source, "command.hint");
            ConfigFile.options(effective).entrySet().stream().filter(entry -> only == null || only.equals(entry.getKey())).forEach(entry -> {
                var row = Messages.text(language(source), "setting", Messages.text(language(source), "option." + entry.getKey()), entry.getKey(), entry.getValue().getAsString()).copy();
                if (source.getPlayer() != null && MagicShulkerBoxes.config().canEdit(entry.getKey()))
                    row.append(SettingsChat.choices(language(source), entry.getKey(), "/msb set", "/msb reset", "command.inherit"));
                reply(source, row);
            });
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
        return reset(source, null);
    }

    private static int reset(CommandSourceStack source, String key) throws CommandSyntaxException {
        var player = source.getPlayerOrException();
        if (key != null && !ConfigFile.optionNames().contains(key)) return failure(source, "invalid");
        if (key != null && !MagicShulkerBoxes.config().canEdit(key)) return failure(source, "locked");
        if (!MagicShulkerBoxes.config().allowPlayerSettings) return failure(source, "locked");
        try {
            var store = MagicShulkerBoxes.players(source.getServer());
            var overrides = key == null ? new JsonObject() : ConfigFile.editable(store.read(player.getUUID()), MagicShulkerBoxes.config().playerEditableSettings);
            if (key != null) overrides.remove(key);
            store.saveAllowed(player.getUUID(), overrides, MagicShulkerBoxes.config());
            SettingsNetwork.acknowledge(player);
            if (key == null) reply(source, "reset");
            else reply(source, "reset_option", Messages.text(language(source), "option." + key));
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
