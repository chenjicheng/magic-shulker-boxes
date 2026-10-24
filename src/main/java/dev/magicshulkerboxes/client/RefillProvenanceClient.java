package dev.magicshulkerboxes.client;

import dev.magicshulkerboxes.ItemFingerprint;
import dev.magicshulkerboxes.RefillProvenanceNetwork;
import dev.magicshulkerboxes.RefillSources;
import java.util.List;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/** Renders provenance from the ledger snapshot; the item itself carries no marker. */
final class RefillProvenanceClient {
    private static List<RefillProvenanceNetwork.WireEntry> entries = List.of();

    private RefillProvenanceClient() {}

    static void register() {
        ItemTooltipCallback.EVENT.register((stack, context, type, lines) -> {
            var description = description(stack);
            if (description != null) lines.add(description);
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> entries = List.of());
    }

    static void receive(List<RefillProvenanceNetwork.WireEntry> snapshot) {
        entries = List.copyOf(snapshot);
    }

    private static Component description(ItemStack stack) {
        var client = Minecraft.getInstance();
        var player = client.player;
        if (player == null || entries.isEmpty()) return null;
        int slot = inventorySlot(player.getInventory(), stack);
        if (slot < 0) return null;
        var fingerprint = ItemFingerprint.of(stack, player.registryAccess());
        if (fingerprint.length() != 64) return null;
        for (var entry : entries) {
            if (entry.anchorSlot() != slot || !entry.fingerprint().equals(fingerprint)) continue;
            Component origin = entry.ender()
                    ? ClientSettings.text("ledger.source.ender", entry.sourceSlot() - RefillSources.ENDER_START + 1)
                    : entry.sourceSlot() == Inventory.SLOT_OFFHAND
                            ? ClientSettings.text("ledger.source.offhand")
                            : entry.sourceSlot() < 9
                                    ? ClientSettings.text("ledger.source.hotbar", entry.sourceSlot() + 1)
                                    : ClientSettings.text("ledger.source.inventory", entry.sourceSlot() - 8);
            return ClientSettings.text("refill.provenance", origin, entry.remaining());
        }
        return null;
    }

    /** Identity prevents a same-looking stack in a chest or recipe book from claiming the marker. */
    private static int inventorySlot(Inventory inventory, ItemStack stack) {
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) if (inventory.getItem(slot) == stack) return slot;
        return -1;
    }
}
