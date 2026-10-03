package dev.magicshulkerboxes.client;

import com.google.gson.JsonObject;
import dev.isxander.yacl3.api.*;
import dev.isxander.yacl3.gui.YACLScreen;
import dev.magicshulkerboxes.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** Optional GUI adapter. Persistence and network authority remain outside YACL bindings. */
public final class SettingsGui {
    private SettingsGui() {}
    private enum OnOff { ON, OFF }
    private static OnOff onOff(boolean value) { return value ? OnOff.ON : OnOff.OFF; }
    private static Component text(String key, Object... args) { return ClientSettings.text(key, args); }
    private static Screen invalid(Screen parent) {
        return new ConfirmScreen(ignored -> Minecraft.getInstance().setScreen(parent), text("title"), text("gui.invalid_file"));
    }
    private static String group(String key) {
        return switch (key) {
            case "schematicRefill", "enderChestRefill", "refillFullStack", "refillMakeSpace", "refillFailureMessages" -> "refill";
            case "ipnRefill" -> "ipn";
            case "craftRefill" -> "craft";
            case "carpetRefill" -> "carpet";
            case "useMatchingBoxes", "useEmptyBoxes", "useMixedBoxes", "allowOtherSingleTypeBoxes", "includeOffhand" -> "boxes";
            case "splitStackedBoxes", "makeSpaceMode", "useHotbarForSpace", "allowPartialStacksForSpace", "allowMixedItemsWhenMakingSpace", "preferExistingBoxesBeforeMakingSpace" -> "space";
            default -> "pickup";
        };
    }
    private static OptionDescription description(String key, JsonObject defaults) {
        var description = text("description." + key).copy();
        if (defaults != null && defaults.has(key)) {
            Component value = key.equals("makeSpaceMode") ? text("gui.space." + defaults.get(key).getAsString())
                    : text(defaults.get(key).getAsBoolean() ? "gui.toggle.ON" : "gui.toggle.OFF");
            description.append("\n\n").append(text("gui.inherit_hint", value));
            if (!ClientSettings.canEdit(key)) description.append("\n\n").append(text("gui.field_locked", text("option." + key)));
        }
        return OptionDescription.of(description);
    }

    public static Screen personal(Screen parent) {
        try {
            var personal = ClientSettings.preferences();
            personal.keySet().removeIf(key -> !ClientSettings.canEdit(key));
            var draft = new SettingsDraft(personal);
            long revision = ClientSettings.session.revision();
            var defaults = ClientSettings.defaults();
            var editable = new ArrayList<Option<?>>();
            var category = ConfigCategory.createBuilder().name(text("gui.personal"))
                    .option(LabelOption.create(text("gui.status." + ClientSettings.session.mode().name())));
            if (defaults != null && ConfigFile.optionNames().stream().anyMatch(key -> !ClientSettings.canEdit(key)))
                category.option(LabelOption.create(text("gui.partial_permissions")));
            if (ClientSettings.session.pending()) category.option(LabelOption.create(text(
                    ClientSettings.session.recovering() ? "gui.recovering" : "gui.pending")));
            if (defaults != null && defaults.has("pickupStorageEnabled") && !defaults.get("pickupStorageEnabled").getAsBoolean()) {
                category.option(LabelOption.create(text("gui.pickup_off")));
            }
            if (defaults != null && defaults.has("schematicRefill") && !defaults.get("schematicRefill").getAsBoolean()) {
                category.option(LabelOption.create(text("gui.refill_off")));
            }
            for (String section : List.of("pickup", "boxes", "space", "refill", "ipn", "craft", "carpet")) {
                var group = OptionGroup.createBuilder().name(text("gui.group." + section));
                for (String key : ConfigFile.optionNames()) {
                    if (!group(key).equals(section)) continue;
                    Option<?> option;
                    if (key.equals("makeSpaceMode")) {
                        option = Option.<SettingsDraft.Space>createBuilder().name(text("option." + key))
                                .description(description(key, defaults))
                                .binding(SettingsDraft.Space.INHERIT, draft::space, draft::space)
                                .customController(o -> new SettingsChoice<>(o,
                                        v -> SettingsLabels.personal(key, v.name(), ClientSettings.defaults())))
                                .available(ClientSettings.session.editable(revision) && ClientSettings.canEdit(key)).build();
                    } else {
                        option = Option.<SettingsDraft.Toggle>createBuilder().name(text("option." + key))
                                .description(description(key, defaults))
                                .binding(SettingsDraft.Toggle.INHERIT, () -> draft.toggle(key), v -> draft.toggle(key, v))
                                .customController(o -> new SettingsChoice<>(o,
                                        v -> SettingsLabels.personal(key, v.name(), ClientSettings.defaults())))
                                .available(ClientSettings.session.editable(revision) && ClientSettings.canEdit(key)).build();
                    }
                    group.option(option);
                    editable.add(option);
                }
                category.group(group.build());
            }
            var builder = YetAnotherConfigLib.createBuilder().title(text("title")).category(category.build())
                    .save(() -> ClientSettings.savePersonal(revision, draft.values()));
            return new GuardedScreen(builder.build(), parent, revision, ClientSettings.session::revision, editable, false);
        } catch (IOException exception) { return invalid(parent); }
    }

    private static Screen local(Screen parent) {
        try {
            var client = Minecraft.getInstance();
            var server = client.getSingleplayerServer();
            if (client.getConnection() != null && server == null) return parent;
            long revision = ClientSettings.session.connectionRevision();
            var original = com.google.gson.JsonParser.parseString(ConfigFile.json(ConfigFile.load(ClientSettings.localPath()))).getAsJsonObject();
            var values = original.deepCopy();
            var defaults = com.google.gson.JsonParser.parseString(ConfigFile.json(new ServerConfig())).getAsJsonObject();
            var category = ConfigCategory.createBuilder().name(text("gui.local"))
                    .option(LabelOption.create(text("gui.local_hint")));
            var editable = new ArrayList<Option<?>>();
            for (String section : List.of("pickup", "boxes", "space", "refill", "ipn", "craft", "carpet")) {
                var group = OptionGroup.createBuilder().name(text("gui.group." + section));
                for (String key : values.keySet()) {
                    if (key.equals("playerEditableSettings")) continue;
                    if (!group(key).equals(section)) continue;
                    Option<?> option;
                    if (key.equals("makeSpaceMode")) {
                        option = Option.<StorageConfig.MakeSpaceMode>createBuilder().name(text("option." + key))
                                .description(description(key, null))
                                .binding(StorageConfig.MakeSpaceMode.MOVE_TO_BOX,
                                        () -> StorageConfig.MakeSpaceMode.valueOf(values.get(key).getAsString()), v -> values.addProperty(key, v.name()))
                                .customController(o -> new SettingsChoice<>(o, v -> text("gui.space." + v.name()))).build();
                    } else {
                        option = Option.<OnOff>createBuilder().name(text("option." + key)).description(description(key, null))
                                .binding(onOff(defaults.get(key).getAsBoolean()), () -> onOff(values.get(key).getAsBoolean()),
                                        v -> values.addProperty(key, v == OnOff.ON))
                                .customController(o -> new SettingsChoice<>(o, v -> text("gui.toggle." + v.name()))).build();
                    }
                    group.option(option);
                    editable.add(option);
                }
                category.group(group.build());
            }
            var permissions = OptionGroup.createBuilder().name(text("gui.permissions"))
                    .option(LabelOption.create(text("gui.permissions_hint")));
            for (String key : ConfigFile.optionNames()) {
                var option = Option.<OnOff>createBuilder().name(text("option." + key))
                        .description(OptionDescription.of(text("gui.permission_hint", text("option." + key))))
                        .binding(OnOff.ON, () -> onOff(values.getAsJsonArray("playerEditableSettings").contains(new com.google.gson.JsonPrimitive(key))), value -> {
                            var names = values.getAsJsonArray("playerEditableSettings");
                            var name = new com.google.gson.JsonPrimitive(key);
                            if (value == OnOff.ON && !names.contains(name)) names.add(name);
                            else if (value == OnOff.OFF) names.remove(name);
                        }).customController(o -> new SettingsChoice<>(o, v -> text("gui.toggle." + v.name()))).build();
                permissions.option(option); editable.add(option);
            }
            category.group(permissions.build());
            var yacl = YetAnotherConfigLib.createBuilder().title(text("title")).category(category.build())
                    .save(() -> ClientSettings.saveLocal(server, revision, original, values)).build();
            return new GuardedScreen(yacl, parent, revision, ClientSettings.session::connectionRevision, editable, true);
        } catch (IOException exception) { return invalid(parent); }
    }

    private static final class GuardedScreen extends YACLScreen {
        private final long revision;
        private final LongSupplier currentRevision;
        private final List<Option<?>> options;
        private final Screen parent;
        private final boolean local;
        private boolean invalidated;
        GuardedScreen(YetAnotherConfigLib config, Screen parent, long revision, LongSupplier currentRevision, List<Option<?>> options, boolean local) {
            super(config, parent);
            this.revision = revision;
            this.currentRevision = currentRevision;
            this.options = options;
            this.parent = parent;
            this.local = local;
        }
        @Override protected void init() {
            super.init();
            // Each page has its own YACL save transaction, while navigation stays on one level.
            if (tabNavigationBar != null) removeWidget(tabNavigationBar);
            boolean hasLocal = minecraft.getConnection() == null || minecraft.getSingleplayerServer() != null;
            int buttonWidth = hasLocal ? (width - 64) / 2 : width - 60;
            var personal = addRenderableWidget(Button.builder(text("gui.personal"), button -> switchPage(false))
                    .bounds(30, 2, buttonWidth, 20).build());
            personal.active = local;
            if (hasLocal) {
                var world = addRenderableWidget(Button.builder(text(minecraft.getConnection() == null ? "gui.local_defaults" : "gui.world"), button -> switchPage(true))
                        .bounds(34 + buttonWidth, 2, buttonWidth, 20).build());
                world.active = !local;
            }
        }
        private void switchPage(boolean toLocal) {
            Runnable open = () -> minecraft.setScreen(toLocal ? SettingsGui.local(parent) : SettingsGui.personal(parent));
            if (pendingChanges()) {
                minecraft.setScreen(new ConfirmScreen(discard -> {
                    if (discard) open.run(); else minecraft.setScreen(this);
                }, text("gui.unsaved_title"), text("gui.unsaved_body"), text("gui.discard"), text("gui.back")));
            } else open.run();
        }
        @Override public void tick() {
            super.tick();
            if (!invalidated && currentRevision.getAsLong() != revision) {
                invalidated = true;
                options.forEach(option -> option.setAvailable(false));
                ClientSettings.notice("gui.stale");
            }
        }
        @Override public void finishOrSave() {
            if (pendingChanges() && currentRevision.getAsLong() != revision) {
                ClientSettings.notice("gui.stale");
                return;
            }
            super.finishOrSave();
        }
    }
}
