package dev.magicshulkerboxes.client;

import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.controller.ValueFormatter;
import dev.isxander.yacl3.api.utils.Dimension;
import dev.isxander.yacl3.gui.AbstractWidget;
import dev.isxander.yacl3.gui.YACLScreen;
import dev.isxander.yacl3.gui.controllers.cycling.CyclingControllerElement;
import dev.isxander.yacl3.gui.controllers.cycling.CyclingListController;
import dev.isxander.yacl3.gui.utils.GuiUtils;
import java.util.Arrays;
import net.minecraft.client.gui.ComponentPath;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.navigation.FocusNavigationEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** YACL's native cycling behavior, with a plain label and a separate value button. */
final class SettingsChoice<E extends Enum<E>> extends CyclingListController<E> {
    private final ValueFormatter<E> formatter;
    SettingsChoice(Option<E> option, ValueFormatter<E> formatter) {
        super(option, Arrays.asList(option.pendingValue().getDeclaringClass().getEnumConstants()), formatter::format);
        this.formatter = formatter;
    }

    @Override public AbstractWidget provideWidget(YACLScreen screen, Dimension<Integer> dimension) {
        return new ChoiceWidget(screen, dimension);
    }

    private final class ChoiceWidget extends CyclingControllerElement {
        private final ResetButton reset = new ResetButton(option());
        private boolean resetFocused;
        ChoiceWidget(YACLScreen screen, Dimension<Integer> dimension) {
            super(SettingsChoice.this, screen, dimension);
        }
        private Dimension<Integer> field() {
            int widest = Arrays.stream(option().pendingValue().getDeclaringClass().getEnumConstants())
                    .map(formatter::format).mapToInt(textRenderer::width).max().orElse(40);
            int width = Math.min((getDimension().width() - 22) * 3 / 5, Math.max(60, widest + 12));
            return Dimension.ofInt(getDimension().xLimit() - 22 - width, getDimension().y(), width, getDimension().height());
        }
        @Override public boolean canReset() { return false; } // This row owns the larger, named reset control.
        private boolean resetAvailable() { return isAvailable() && !option().isPendingValueDefault(); }
        @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
            hovered = isMouseOver(mouseX, mouseY);
            var field = field();
            var label = option().changed() ? modifiedOptionName : option().name();
            String name = GuiUtils.shortenString(label.getString(), textRenderer, field.x() - getDimension().x() - 10, "...");
            int color = isAvailable() ? 0xFFFFFFFF : inactiveColor;
            graphics.drawString(textRenderer, Component.literal(name).setStyle(label.getStyle()), getDimension().x() + 5, getTextY(), color);
            drawButtonRect(graphics, field.x(), field.y(), field.xLimit(), field.yLimit(),
                    isAvailable() && (field.isPointInside(mouseX, mouseY) || (focused && !resetFocused)), isAvailable());
            String value = GuiUtils.shortenString(formatValue().getString(), textRenderer, field.width() - 10, "...");
            graphics.drawString(textRenderer, value, field.x() + (field.width() - textRenderer.width(value)) / 2, getTextY(), color);
            reset.setX(getDimension().xLimit() - 20);
            reset.setY(getDimension().y());
            reset.active = resetAvailable();
            reset.setFocused(focused && resetFocused);
            reset.render(graphics, mouseX, mouseY, delta);
        }
        @Override public boolean onMouseClicked(double x, double y, int button) {
            if (!isAvailable()) return false;
            if (reset.isMouseOver(x, y)) {
                if (button != 0 || !resetAvailable()) return false;
                resetFocused = true;
                option().requestSetDefault();
                playDownSound();
                return true;
            }
            if (!field().isPointInside((int) x, (int) y)) return false;
            resetFocused = false;
            return super.onMouseClicked(x, y, button);
        }
        @Override public boolean onKeyPressed(int key, int scanCode, int modifiers) {
            if (!isAvailable() || !focused) return false;
            if (!resetFocused) return super.onKeyPressed(key, scanCode, modifiers);
            if (resetAvailable() && (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER || key == GLFW.GLFW_KEY_SPACE)) {
                option().requestSetDefault();
                playDownSound();
                return true;
            }
            return false;
        }
        @Override public ComponentPath nextFocusPath(FocusNavigationEvent event) {
            boolean forward = !(event instanceof FocusNavigationEvent.TabNavigation tab) || tab.forward();
            if (!focused) {
                resetFocused = !forward && resetAvailable();
                return ComponentPath.leaf(this);
            }
            if (event instanceof FocusNavigationEvent.TabNavigation && forward != resetFocused && resetAvailable()) {
                resetFocused = forward;
                return ComponentPath.leaf(this);
            }
            return null;
        }
        @Override public void updateNarration(NarrationElementOutput output) {
            if (resetFocused) reset.updateWidgetNarration(output); else super.updateNarration(output);
        }
    }

    /** Retain Minecraft's button behavior and skin, replacing only the undersized font glyph. */
    private static final class ResetButton extends Button.Plain {
        ResetButton(Option<?> option) {
            super(0, 0, 20, 20, Component.translatable("magic_shulker_boxes.gui.reset_option"),
                    button -> option.requestSetDefault(), DEFAULT_NARRATION);
            setTooltip(Tooltip.create(getMessage()));
        }
        @Override protected void renderContents(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
            renderDefaultSprite(graphics);
            int x = getX() + 4, y = getY() + 4;
            int color = active ? 0xFFFFFFFF : 0xFF808080;
            graphics.fill(x + 2, y + 2, x + 10, y + 4, color);
            graphics.fill(x + 9, y + 3, x + 12, y + 9, color);
            graphics.fill(x + 3, y + 9, x + 11, y + 11, color);
            graphics.fill(x + 1, y + 7, x + 4, y + 10, color);
            for (int row = 0; row < 7; row++) {
                int start = Math.abs(row - 3);
                graphics.fill(x + start, y + row, x + start + 2, y + row + 1, color);
            }
        }
    }
}
