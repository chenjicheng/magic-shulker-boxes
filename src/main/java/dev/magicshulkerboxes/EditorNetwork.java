package dev.magicshulkerboxes;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

/** Optional GUI protocol. Request IDs prevent stale replies from committing client files. */
public final class EditorNetwork {
    public static final int SAVED = 0, LOCKED = 1, INVALID = 2, BUSY = 3, FAILED = 4, SNAPSHOT = 5,
            QUERY_BUSY = 6, QUERY_FAILED = 7;
    private EditorNetwork() {}
    private static final Map<UUID, Integer> LAST_SAVE = new HashMap<>();
    private static final Map<UUID, Integer> LAST_QUERY = new HashMap<>();
    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> id(String path) {
        return new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("magic_shulker_boxes", path));
    }
    public record State(boolean allowed, String defaults) implements CustomPacketPayload {
        public static final Type<State> ID = id("editor_state_v4");
        public static final StreamCodec<RegistryFriendlyByteBuf, State> CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, State::allowed, ByteBufCodecs.stringUtf8(4096), State::defaults, State::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }
    public record Save(int request, String json) implements CustomPacketPayload {
        public static final Type<Save> ID = id("editor_save_v4");
        public static final StreamCodec<RegistryFriendlyByteBuf, Save> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Save::request, ByteBufCodecs.stringUtf8(4096), Save::json, Save::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }
    public record Result(int request, int status, String json) implements CustomPacketPayload {
        public static final Type<Result> ID = id("editor_result_v4");
        public static final StreamCodec<RegistryFriendlyByteBuf, Result> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Result::request, ByteBufCodecs.VAR_INT, Result::status,
                ByteBufCodecs.stringUtf8(4096), Result::json, Result::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }
    public record Query(int request) implements CustomPacketPayload {
        public static final Type<Query> ID = id("editor_query_v4");
        public static final StreamCodec<RegistryFriendlyByteBuf, Query> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Query::request, Query::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }
    public static void register() {
        PayloadTypeRegistry.playS2C().register(State.ID, State.CODEC);
        PayloadTypeRegistry.playS2C().register(Result.ID, Result.CODEC);
        PayloadTypeRegistry.playC2S().register(Save.ID, Save.CODEC);
        PayloadTypeRegistry.playC2S().register(Query.ID, Query.CODEC);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            LAST_SAVE.remove(handler.player.getUUID()); LAST_QUERY.remove(handler.player.getUUID());
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> { LAST_SAVE.clear(); LAST_QUERY.clear(); });
        ServerPlayNetworking.registerGlobalReceiver(Save.ID, (payload, context) -> {
            if (ServerPlayNetworking.canSend(context.player(), Result.ID)) {
                ServerPlayNetworking.send(context.player(), save(context.player(), payload));
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(Query.ID, (payload, context) -> {
            if (ServerPlayNetworking.canSend(context.player(), Result.ID)) {
                ServerPlayNetworking.send(context.player(), query(context.player(), payload));
            }
        });
    }

    public static void sendState(ServerPlayer player) {
        if (ServerPlayNetworking.canSend(player, State.ID)) {
            ServerPlayNetworking.send(player, new State(MagicShulkerBoxes.config().allowPlayerSettings,
                    ConfigFile.options(MagicShulkerBoxes.config()).toString()));
        }
    }

    public static Result save(ServerPlayer player, Save payload) {
        if (!MagicShulkerBoxes.config().allowPlayerSettings) return new Result(payload.request(), LOCKED, "{}");
        int now = player.level().getServer().getTickCount();
        var last = LAST_SAVE.get(player.getUUID());
        if (last != null && now - last < 20) return new Result(payload.request(), BUSY, "{}");
        LAST_SAVE.put(player.getUUID(), now);
        com.google.gson.JsonObject validated;
        try {
            validated = ConfigFile.parsePreferences(payload.json());
        } catch (IOException exception) { return new Result(payload.request(), INVALID, "{}"); }
        try {
            MagicShulkerBoxes.players(player.level().getServer()).save(player.getUUID(), validated);
            return new Result(payload.request(), SAVED, validated.toString());
        } catch (IOException exception) {
            MagicShulkerBoxes.LOGGER.error("Cannot save GUI preferences / 无法保存界面设置", exception);
            return new Result(payload.request(), FAILED, "{}");
        }
    }

    /** Read only the authenticated player's persisted preferences, even if personal edits are now locked. */
    public static Result query(ServerPlayer player, Query payload) {
        int now = player.level().getServer().getTickCount();
        var last = LAST_QUERY.get(player.getUUID());
        if (last != null && now - last < 20) return new Result(payload.request(), QUERY_BUSY, "{}");
        LAST_QUERY.put(player.getUUID(), now);
        try {
            var values = MagicShulkerBoxes.players(player.level().getServer()).read(player.getUUID());
            return new Result(payload.request(), SNAPSHOT, values.toString());
        } catch (IOException exception) {
            MagicShulkerBoxes.LOGGER.error("Cannot reconcile preferences / 无法重新同步个人设置", exception);
            return new Result(payload.request(), QUERY_FAILED, "{}");
        }
    }
}
