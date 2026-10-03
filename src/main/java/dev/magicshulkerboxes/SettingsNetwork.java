package dev.magicshulkerboxes;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public final class SettingsNetwork {
    private static final Map<UUID, Integer> LAST_SYNC = new HashMap<>();
    private SettingsNetwork() {}

    public record Policy(boolean allowed) implements CustomPacketPayload {
        public static final Type<Policy> ID = new Type<>(Identifier.fromNamespaceAndPath("magic_shulker_boxes", "policy_v4"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Policy> CODEC = StreamCodec.composite(ByteBufCodecs.BOOL, Policy::allowed, Policy::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    public record Preferences(String json) implements CustomPacketPayload {
        public static final Type<Preferences> ID = new Type<>(Identifier.fromNamespaceAndPath("magic_shulker_boxes", "preferences_v4"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Preferences> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(ConfigFile.MAX_PREFERENCES_LENGTH), Preferences::json, Preferences::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(Policy.ID, Policy.CODEC);
        PayloadTypeRegistry.playS2C().register(Preferences.ID, Preferences.CODEC);
        PayloadTypeRegistry.playC2S().register(Preferences.ID, Preferences.CODEC);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> sendPolicy(handler.player));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> LAST_SYNC.remove(handler.player.getUUID()));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> LAST_SYNC.clear());
        // Fabric invokes object-payload receivers on the server thread.
        ServerPlayNetworking.registerGlobalReceiver(Preferences.ID, (payload, context) -> {
            var player = context.player();
            if (!MagicShulkerBoxes.config().allowPlayerSettings) return;
            int now = context.server().getTickCount();
            var last = LAST_SYNC.put(player.getUUID(), now);
            if (last != null && now - last < 20) return;
            try {
                if (accept(player, payload.json())) acknowledge(player);
            } catch (IOException exception) {
                player.sendSystemMessage(Messages.text(player.clientInformation().language(), "invalid"));
                MagicShulkerBoxes.LOGGER.warn("Rejected player settings / 玩家设置被拒绝: {}", player.getUUID(), exception);
            }
        });
    }

    /** Identity comes only from the authenticated connection; the payload contains no player identifier. */
    public static boolean accept(ServerPlayer player, String json) throws IOException {
        if (!MagicShulkerBoxes.config().allowPlayerSettings) return false;
        var overrides = ConfigFile.parsePreferences(json);
        MagicShulkerBoxes.players(player.level().getServer()).save(player.getUUID(), overrides);
        return true;
    }

    public static void acknowledge(ServerPlayer player) throws IOException {
        if (ServerPlayNetworking.canSend(player, Preferences.ID)) {
            var overrides = MagicShulkerBoxes.players(player.level().getServer()).read(player.getUUID());
            ServerPlayNetworking.send(player, new Preferences(overrides.toString()));
        }
    }

    public static void sendPolicy(ServerPlayer player) {
        LAST_SYNC.remove(player.getUUID());
        EditorNetwork.sendState(player);
        if (ServerPlayNetworking.canSend(player, Policy.ID)) {
            ServerPlayNetworking.send(player, new Policy(MagicShulkerBoxes.config().allowPlayerSettings));
        }
    }

    public static void broadcastPolicy(MinecraftServer server) {
        server.getPlayerList().getPlayers().forEach(SettingsNetwork::sendPolicy);
    }
}
