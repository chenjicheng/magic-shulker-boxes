package dev.magicshulkerboxes.client;

import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.gui.OptionListWidget;
import dev.isxander.yacl3.gui.WidgetAndType;
import dev.isxander.yacl3.gui.YACLScreen;
import dev.magicshulkerboxes.*;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.client.gui.components.Button;
import org.lwjgl.glfw.GLFW;

/** Actual YACL pointer and keyboard flow, reused for integrated and TCP servers. */
public final class RefillAmountClientFlow {
    private RefillAmountClientFlow() {}
    public static void run(ClientGameTestContext context, TestServerContext server) {
        server.runOnServer(actual -> {
            var config = MagicShulkerBoxes.config();
            config.allowPlayerSettings = true;
            config.refillAmount = 64;
            config.playerEditableSettings = java.util.List.of("refillAmount");
            try {
                var player = actual.getPlayerList().getPlayers().getFirst();
                MagicShulkerBoxes.players(actual).save(player.getUUID(), new com.google.gson.JsonObject());
                SettingsNetwork.acknowledge(player);
            } catch (java.io.IOException exception) { throw new AssertionError(exception); }
            SettingsNetwork.broadcastPolicy(actual);
        });
        context.waitFor(client -> ClientSettings.canEdit("refillAmount") && ClientSettings.defaults() != null);
        for (String language : java.util.List.of("en_us", "zh_cn")) {
            context.runOnClient(client -> {
                client.options.languageCode = language;
                client.getLanguageManager().setSelected(language);
                client.reloadResourcePacks();
            });
            context.waitFor(client -> client.getOverlay() == null
                    && net.minecraft.locale.Language.getInstance().getOrDefault("magic_shulker_boxes.option.refillAmount")
                            .equals(Messages.pattern(language, "option.refillAmount")));
            openAmount(context, server);
            var point = context.computeOnClient(client -> {
                var entry = amountEntry((YACLScreen) client.screen);
                check(entry.option.available(), "Quantity slider must be available");
                var dim = sliderBounds(entry);
                check(dim.centerY() > 30 && dim.centerY() < client.screen.height - 30, "Slider is within the visible list");
                return new double[]{dim.centerX() * client.getWindow().getGuiScale(), dim.centerY() * client.getWindow().getGuiScale()};
            });
            context.getInput().setCursorPos(point[0], point[1]);
            context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
            int pointed = context.computeOnClient(client -> amountOption((YACLScreen)client.screen).pendingValue());
            check(pointed > 1 && pointed < 64, "Pointer selects an interior quantity");
            context.getInput().pressKey(GLFW.GLFW_KEY_LEFT);
            int selected = pointed - 1;
            context.runOnClient(client -> check(amountOption((YACLScreen)client.screen).pendingValue() == selected, "Keyboard changes quantity by one"));
            context.takeScreenshot("refill-amount-" + language);
            click(context, context.computeOnClient(client -> tab((YACLScreen)client.screen).saveFinishedButton));
            waitForAmount(context, server, selected);

            openAmount(context, server);
            context.runOnClient(client -> amountOption((YACLScreen)client.screen).requestSet(17));
            click(context, context.computeOnClient(client -> tab((YACLScreen)client.screen).cancelResetButton));
            server.runOnServer(actual -> check(MagicShulkerBoxes.configFor(actual.getPlayerList().getPlayers().getFirst()).refillAmount == selected,
                    "Cancel preserves the saved quantity"));
            openAmount(context, server);
            context.runOnClient(client -> {
                var option = amountOption((YACLScreen)client.screen);
                check(option.pendingValue() == selected, "Reopened slider reads the acknowledged value");
                option.requestSet(0);
            });
            context.takeScreenshot("refill-amount-inherit-" + language);
            click(context, context.computeOnClient(client -> tab((YACLScreen)client.screen).saveFinishedButton));
            waitForAmount(context, server, 64);
            context.runOnClient(client -> {
                try { check(!ClientSettings.preferences().has("refillAmount"), "Inheritance removes the local override"); }
                catch (java.io.IOException exception) { throw new AssertionError(exception); }
            });
        }
        if (context.computeOnClient(client -> client.getSingleplayerServer() != null)) {
            context.setScreen(() -> SettingsGui.personal(null));
            click(context, context.computeOnClient(client -> client.screen.children().stream()
                    .filter(Button.class::isInstance).map(Button.class::cast)
                    .filter(button -> button.getMessage().getString().equals(ClientSettings.text("gui.world").getString()))
                    .findFirst().orElseThrow()));
            context.runOnClient(client -> list((YACLScreen)client.screen).updateSearchQuery(ClientSettings.text("option.refillAmount").getString()));
            context.waitTicks(3);
            var point = context.computeOnClient(client -> {
                var bounds = sliderBounds(amountEntry((YACLScreen)client.screen));
                return new double[]{bounds.centerX()*client.getWindow().getGuiScale(), bounds.centerY()*client.getWindow().getGuiScale()};
            });
            context.getInput().setCursorPos(point[0], point[1]);
            context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
            int chosen = context.computeOnClient(client -> amountOption((YACLScreen)client.screen).pendingValue());
            check(chosen >= 1 && chosen < 64, "World slider chooses a valid quantity");
            context.takeScreenshot("refill-amount-world-zh_cn");
            click(context, context.computeOnClient(client -> tab((YACLScreen)client.screen).saveFinishedButton));
            for (int i=0; i<200 && !server.computeOnServer(actual -> MagicShulkerBoxes.config().refillAmount == chosen); i++) context.waitTick();
            server.runOnServer(actual -> {
                check(MagicShulkerBoxes.config().refillAmount == chosen, "World quantity is active");
                try { check(ConfigFile.load(ClientSettings.localPath()).refillAmount == chosen, "World quantity is persisted"); }
                catch (java.io.IOException exception) { throw new AssertionError(exception); }
            });
        }
        context.runOnClient(client -> client.setScreen(null));
    }
    private static void openAmount(ClientGameTestContext context, TestServerContext server) {
        context.runOnClient(client -> client.setScreen(null));
        // Saves are rate-limited for 20 server ticks. A singleplayer settings screen pauses the world.
        int readyTick = server.computeOnServer(actual -> actual.getTickCount()) + 20;
        for (int i = 0; i < 100 && server.computeOnServer(actual -> actual.getTickCount() < readyTick); i++) context.waitTick();
        check(server.computeOnServer(actual -> actual.getTickCount() >= readyTick), "Server save cooldown elapsed");
        context.setScreen(() -> SettingsGui.personal(null));
        context.runOnClient(client -> list((YACLScreen)client.screen).updateSearchQuery(ClientSettings.text("option.refillAmount").getString()));
        context.waitTicks(3);
    }
    private static YACLScreen.CategoryTab tab(YACLScreen screen) { return (YACLScreen.CategoryTab)screen.tabManager.getCurrentTab(); }
    private static OptionListWidget list(YACLScreen screen) {
        try {
            var field = YACLScreen.CategoryTab.class.getDeclaredField("optionList");
            field.setAccessible(true);
            return (OptionListWidget)((WidgetAndType<?>)field.get(tab(screen))).getType();
        } catch (ReflectiveOperationException exception) { throw new AssertionError(exception); }
    }
    private static dev.isxander.yacl3.api.utils.Dimension<Integer> sliderBounds(OptionListWidget.OptionEntry entry) {
        try {
            var field = entry.widget.getClass().getDeclaredField("sliderBounds");
            field.setAccessible(true);
            @SuppressWarnings("unchecked") // The installed integer slider owns integer pixel bounds.
            var bounds = (dev.isxander.yacl3.api.utils.Dimension<Integer>)field.get(entry.widget);
            return bounds;
        } catch (ReflectiveOperationException exception) { throw new AssertionError(exception); }
    }

    private static OptionListWidget.OptionEntry amountEntry(YACLScreen screen) {
        return list(screen).children().stream().filter(OptionListWidget.OptionEntry.class::isInstance)
                .map(OptionListWidget.OptionEntry.class::cast)
                .filter(entry -> entry.option.name().getString().equals(ClientSettings.text("option.refillAmount").getString()))
                .findFirst().orElseThrow(() -> new AssertionError("Rendered quantity slider missing"));
    }
    @SuppressWarnings("unchecked") // The quantity option is declared as Option<Integer> in SettingsGui.
    private static Option<Integer> amountOption(YACLScreen screen) { return (Option<Integer>)amountEntry(screen).option; }
    private static void click(ClientGameTestContext context, Button button) {
        var point = context.computeOnClient(client -> new double[]{(button.getX() + button.getWidth()/2.0)*client.getWindow().getGuiScale(),
                (button.getY()+button.getHeight()/2.0)*client.getWindow().getGuiScale()});
        context.getInput().setCursorPos(point[0], point[1]);
        context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
    }
    private static void waitForAmount(ClientGameTestContext context, TestServerContext server, int expected) {
        for (int i=0; i<200; i++) {
            if (server.computeOnServer(actual -> MagicShulkerBoxes.configFor(actual.getPlayerList().getPlayers().getFirst()).refillAmount == expected)
                    && context.computeOnClient(client -> !ClientSettings.session.pending())) return;
            context.waitTick();
        }
        throw new AssertionError("Quantity was not confirmed: " + expected);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
