package dev.magicshulkerboxes;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/** Authenticated players edit only their own finite slot mask; no item or player identity crosses this boundary. */
public final class JunkSlotsNetwork {
    public static final int SAVED = 0, SNAPSHOT = 1, STALE = 2, INVALID = 3, BUSY = 4, FAILED = 5;
    private static final Map<UUID, Integer> LAST_SAVE = new HashMap<>();
    private JunkSlotsNetwork() {}

    public record Save(int request, int revision, long mask) implements CustomPacketPayload {
        public static final Type<Save> ID = new Type<>(Identifier.fromNamespaceAndPath("magic_shulker_boxes", "junk_slots_save_v1"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Save> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Save::request, ByteBufCodecs.VAR_INT, Save::revision,
                ByteBufCodecs.VAR_LONG, Save::mask, Save::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }
    public record Query(int request) implements CustomPacketPayload {
        public static final Type<Query> ID = new Type<>(Identifier.fromNamespaceAndPath("magic_shulker_boxes", "junk_slots_query_v1"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Query> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT, Query::request, Query::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }
    public record State(int request, int status, int revision, long mask) implements CustomPacketPayload {
        public static final Type<State> ID = new Type<>(Identifier.fromNamespaceAndPath("magic_shulker_boxes", "junk_slots_state_v1"));
        public static final StreamCodec<RegistryFriendlyByteBuf, State> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, State::request, ByteBufCodecs.VAR_INT, State::status,
                ByteBufCodecs.VAR_INT, State::revision, ByteBufCodecs.VAR_LONG, State::mask, State::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    public static void register() {
        PayloadTypeRegistry.playC2S().register(Save.ID, Save.CODEC);
        PayloadTypeRegistry.playC2S().register(Query.ID, Query.CODEC);
        PayloadTypeRegistry.playS2C().register(State.ID, State.CODEC);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> send(handler.player, snapshot(handler.player, 0, SNAPSHOT)));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> LAST_SAVE.remove(handler.player.getUUID()));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> LAST_SAVE.clear());
        ServerPlayNetworking.registerGlobalReceiver(Save.ID, (payload, context) -> {
            var player = context.player();
            int tick = context.server().getTickCount();
            var previous = LAST_SAVE.get(player.getUUID());
            if (previous != null && previous == tick) { send(player, snapshot(player, payload.request(), BUSY)); return; }
            LAST_SAVE.put(player.getUUID(), tick);
            send(player, accept(player, payload));
        });
        ServerPlayNetworking.registerGlobalReceiver(Query.ID, (payload, context) ->
                send(context.player(), snapshot(context.player(), payload.request(), payload.request() > 0 ? SNAPSHOT : INVALID)));
    }

    public static State accept(ServerPlayer player, Save request) {
        if (!player.isAlive() || request.request() <= 0 || request.revision() < 0)
            return snapshot(player, request.request(), INVALID);
        try {
            JunkSlots.validate(request.mask());
            var stored = MagicShulkerBoxes.junkSlots(player.level().getServer())
                    .update(player.getUUID(), request.revision(), request.mask());
            return new State(request.request(), SAVED, stored.revision(), stored.mask());
        } catch (IllegalArgumentException exception) { return snapshot(player, request.request(), INVALID); }
        catch (JunkSlotStore.Changed exception) { return snapshot(player, request.request(), STALE); }
        catch (IOException exception) {
            MagicShulkerBoxes.LOGGER.error("Cannot save junk slots / 无法保存杂物槽位: {}", player.getUUID(), exception);
            return snapshot(player, request.request(), FAILED);
        }
    }

    private static State snapshot(ServerPlayer player, int request, int status) {
        try {
            var state = MagicShulkerBoxes.junkSlots(player.level().getServer()).read(player.getUUID());
            return new State(request, status, state.revision(), state.mask());
        } catch (IOException exception) {
            return new State(request, FAILED, -1, 0);
        }
    }
    private static void send(ServerPlayer player, State state) {
        if (ServerPlayNetworking.canSend(player, State.ID)) ServerPlayNetworking.send(player, state);
    }
}
