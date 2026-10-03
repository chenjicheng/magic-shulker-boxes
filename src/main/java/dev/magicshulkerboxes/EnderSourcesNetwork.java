package dev.magicshulkerboxes;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/** Read-only client projections come from the authenticated player's own server storage. */
public final class EnderSourcesNetwork {
    private EnderSourcesNetwork() {}

    private static final Map<ServerPlayer, List<ItemStack>> SENT = new WeakHashMap<>();

    public record Snapshot(List<ItemStack> items) implements CustomPacketPayload {
        public static final Type<Snapshot> ID =
                new Type<>(
                        Identifier.fromNamespaceAndPath("magic_shulker_boxes", "ender_sources_v1"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Snapshot> CODEC =
                StreamCodec.of(
                        (buffer, value) -> {
                            if (value.items.size() != 27)
                                throw new IllegalArgumentException("Expected 27 ender slots");
                            for (var item : value.items)
                                ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, item);
                        },
                        buffer -> {
                            var items = new ArrayList<ItemStack>(27);
                            for (int i = 0; i < 27; i++)
                                items.add(ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer));
                            return new Snapshot(List.copyOf(items));
                        });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return ID;
        }
    }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(Snapshot.ID, Snapshot.CODEC);
        ServerPlayConnectionEvents.DISCONNECT.register(
                (handler, server) -> SENT.remove(handler.player));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> SENT.clear());
        ServerTickEvents.END_SERVER_TICK.register(
                server -> {
                    if (server.getTickCount() % 10 != 0) return;
                    for (var player : server.getPlayerList().getPlayers()) sync(player);
                });
    }

    public static void sync(ServerPlayer player) {
        if (!ServerPlayNetworking.canSend(player, Snapshot.ID)) return;
        boolean enabled = MagicShulkerBoxes.configFor(player).enderChestRefill;
        var before = SENT.get(player);
        boolean changed = before == null;
        for (int i = 0; i < 27 && !changed; i++) {
            var current = enabled ? player.getEnderChestInventory().getItem(i) : ItemStack.EMPTY;
            changed = !ItemStack.matches(before.get(i), current);
        }
        if (!changed) return;
        var items = new ArrayList<ItemStack>(27);
        for (int i = 0; i < 27; i++)
            items.add(
                    enabled ? player.getEnderChestInventory().getItem(i).copy() : ItemStack.EMPTY);
        ServerPlayNetworking.send(player, new Snapshot(items));
        SENT.put(player, items);
    }
}
