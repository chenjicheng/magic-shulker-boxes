package dev.magicshulkerboxes;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

/** Vanilla chat suggestions work even when the player has no client mod. */
public final class SettingsChat {
    private SettingsChat() {}

    public static MutableComponent preview(String language, Component label, String command) {
        return label.copy().withStyle(style -> style.withColor(ChatFormatting.AQUA)
                .withClickEvent(new ClickEvent.SuggestCommand(command))
                .withHoverEvent(new HoverEvent.ShowText(Messages.text(language, "command.preview", command))));
    }

    public static Component choices(String language, String option, String setPrefix, String resetPrefix, String resetLabel) {
        var result = Component.empty();
        var values = option.equals("makeSpaceMode") ? List.of("DISABLED", "MOVE_TO_BOX", "DROP_AND_PICKUP") : List.of("true", "false");
        for (var value : values) result.append(" ").append(preview(language, Component.literal("[" + value + "]"), setPrefix + " " + option + " " + value));
        return result.append(" ").append(preview(language, Messages.text(language, resetLabel), resetPrefix + " " + option));
    }
}
