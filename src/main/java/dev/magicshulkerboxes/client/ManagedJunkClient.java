package dev.magicshulkerboxes.client;

import dev.magicshulkerboxes.ManagedJunkBoxes;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.network.chat.Component;

/** The server supplies the item role; no client selection or separate role protocol is needed. */
final class ManagedJunkClient {
    private ManagedJunkClient() {}
    static void register() {
        ItemTooltipCallback.EVENT.register((stack, context, type, lines) -> {
            if (ManagedJunkBoxes.marked(stack)) lines.add(Component.translatable("magic_shulker_boxes.junk.managed"));
        });
    }
}
