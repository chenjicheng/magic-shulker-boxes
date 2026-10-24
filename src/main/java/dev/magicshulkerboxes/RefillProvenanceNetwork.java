package dev.magicshulkerboxes;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/** Read-only projection of the transient provenance ledger; item data stays untouched. */
public final class RefillProvenanceNetwork {
    private RefillProvenanceNetwork() {}

    private static final Map<ServerPlayer, List<WireEntry>> SENT = new WeakHashMap<>();
    private static final int MAX_ENTRIES = 128;

    public record WireEntry(int anchorSlot, int sourceSlot, boolean ender, int remaining, String fingerprint) {}

    public record Snapshot(List<WireEntry> entries) implements CustomPacketPayload {
        public static final Type<Snapshot> ID =
                new Type<>(Identifier.fromNamespaceAndPath("magic_shulker_boxes", "refill_ledger_v1"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Snapshot> CODEC = StreamCodec.of(
                (buffer, value) -> {
                    if (value.entries.size() > MAX_ENTRIES)
                        throw new IllegalArgumentException("Too many provenance entries");
                    buffer.writeVarInt(value.entries.size());
                    for (var entry : value.entries) {
                        buffer.writeVarInt(entry.anchorSlot);
                        buffer.writeVarInt(entry.sourceSlot);
                        buffer.writeBoolean(entry.ender);
                        buffer.writeVarInt(entry.remaining);
                        buffer.writeUtf(entry.fingerprint, 64);
                    }
                },
                buffer -> {
                    int size = buffer.readVarInt();
                    if (size < 0 || size > MAX_ENTRIES) throw new IllegalArgumentException("Invalid provenance count");
                    var entries = new ArrayList<WireEntry>(size);
                    for (int i = 0; i < size; i++) {
                        entries.add(new WireEntry(buffer.readVarInt(), buffer.readVarInt(), buffer.readBoolean(),
                                buffer.readVarInt(), buffer.readUtf(64)));
                    }
                    return new Snapshot(List.copyOf(entries));
                });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return ID;
        }
    }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(Snapshot.ID, Snapshot.CODEC);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> sync(handler.player));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            SENT.remove(handler.player);
            RefillProvenance.clear(handler.player.getUUID());
        });
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
            if (entity instanceof ServerPlayer player) {
                RefillProvenance.clear(player.getUUID());
                sync(player);
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % 20 != 0) return;
            for (var player : server.getPlayerList().getPlayers()) {
                RefillProvenance.reconcile(player.getInventory(), player.getUUID());
                sync(player);
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            SENT.clear();
            RefillProvenance.clearAll();
        });
    }

    public static void sync(ServerPlayer player) {
        if (!ServerPlayNetworking.canSend(player, Snapshot.ID)) return;
        var entries = new ArrayList<WireEntry>();
        for (var entry : RefillProvenance.snapshots(player.getUUID())) {
            var fingerprint = ItemFingerprint.of(entry.prototype(), player.registryAccess());
            if (fingerprint.length() != 64) continue;
            entries.add(new WireEntry(entry.anchorSlot(), entry.sourceSlot(), entry.ender(),
                    entry.remaining(), fingerprint));
        }
        var snapshot = List.copyOf(entries);
        if (snapshot.equals(SENT.get(player))) return;
        ServerPlayNetworking.send(player, new Snapshot(snapshot));
        SENT.put(player, snapshot);
    }
}
