package dev.magicshulkerboxes;

import java.util.Map;
import java.util.WeakHashMap;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.GameType;

/** IPN chooses the candidate; the server verifies snapshots and only extracts into real backpack slots. */
public final class RestockNetwork {
    private RestockNetwork() {}
    private record LastRequest(int tick, int id) {}
    private static final Map<ServerPlayer, LastRequest> REQUESTS = new WeakHashMap<>();

    public record Request(int request, int boxSlot, int contentSlot, int boxCount, int sourceCount,
                          int targetSlot, int targetCount, int eligibleSlots,
                          String sourceFingerprint, String targetFingerprint) implements CustomPacketPayload {
        public static final Type<Request> ID = new Type<>(Identifier.fromNamespaceAndPath("magic_shulker_boxes", "restock_v1"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC = StreamCodec.of((buffer, value) -> {
            buffer.writeVarInt(value.request); buffer.writeVarInt(value.boxSlot); buffer.writeVarInt(value.contentSlot);
            buffer.writeVarInt(value.boxCount); buffer.writeVarInt(value.sourceCount);
            buffer.writeVarInt(value.targetSlot); buffer.writeVarInt(value.targetCount); buffer.writeVarInt(value.eligibleSlots);
            buffer.writeUtf(value.sourceFingerprint, 64); buffer.writeUtf(value.targetFingerprint, 64);
        }, buffer -> new Request(buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(),
                buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(), buffer.readUtf(64), buffer.readUtf(64)));
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    public record Result(int request, boolean success) implements CustomPacketPayload {
        public static final Type<Result> ID = new Type<>(Identifier.fromNamespaceAndPath("magic_shulker_boxes", "restock_result_v1"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Result> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Result::request, ByteBufCodecs.BOOL, Result::success, Result::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    public static void register() {
        PayloadTypeRegistry.playC2S().register(Request.ID, Request.CODEC);
        PayloadTypeRegistry.playS2C().register(Result.ID, Result.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(Request.ID, (request, context) -> {
            boolean success = accept(context.player(), request) > 0;
            if (ServerPlayNetworking.canSend(context.player(), Result.ID)) {
                ServerPlayNetworking.send(context.player(), new Result(request.request(), success));
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> REQUESTS.clear());
    }

    public static int accept(ServerPlayer player, Request request) {
        int now = player.level().getServer().getTickCount();
        var last = REQUESTS.get(player);
        if (request.request() <= 0 || (last != null && (request.request() <= last.id() || now - last.tick() < 10))) return 0;
        REQUESTS.put(player, new LastRequest(now, request.request()));
        var config = MagicShulkerBoxes.configFor(player);
        var mode = player.gameMode.getGameModeForPlayer();
        if (!config.ipnRefill || !player.isAlive() || (mode != GameType.SURVIVAL && mode != GameType.ADVENTURE)
                || player.containerMenu != player.inventoryMenu || !player.inventoryMenu.getCarried().isEmpty()) return 0;
        // IPN's playerStorage area is inventory slots 9..35. Do not expose hotbar, armor, nested boxes or ender chests.
        if (request.boxSlot() < 9 || request.boxSlot() >= 36 || request.contentSlot() < 0 || request.contentSlot() >= 27
                || !((request.targetSlot() >= 0 && request.targetSlot() < 9) || request.targetSlot() == 40)
                || request.eligibleSlots() <= 0 || request.eligibleSlots() >= (1 << 27)
                || (request.eligibleSlots() & (1 << (request.boxSlot() - 9))) == 0) return 0;
        var inventory = player.getInventory();
        // A queued main-hand request stops being relevant as soon as the player selects another hotbar slot.
        if (request.targetSlot() < 9 && inventory.getSelectedSlot() != request.targetSlot()) return 0;
        var target = inventory.getItem(request.targetSlot());
        if (target.getCount() != request.targetCount() || (!target.isEmpty() && request.targetFingerprint().length() != 64)
                || !ItemFingerprint.of(target, player.registryAccess()).equals(request.targetFingerprint())) return 0;
        var box = inventory.getItem(request.boxSlot());
        if (!ShulkerStorage.isShulker(box) || box.getCount() != request.boxCount()) return 0;
        var stored = box.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
        if (stored.stream().count() > 27) return 0;
        var contents = NonNullList.withSize(27, ItemStack.EMPTY); stored.copyInto(contents);
        var source = contents.get(request.contentSlot());
        if (source.isEmpty() || source.getCount() != request.sourceCount() || request.sourceFingerprint().length() != 64
                || !ItemFingerprint.of(source, player.registryAccess()).equals(request.sourceFingerprint())) return 0;
        int moved = ShulkerRefill.takeForRestock(inventory, request.boxSlot(), request.contentSlot(), config, request.eligibleSlots());
        if (moved > 0) player.inventoryMenu.broadcastChanges();
        return moved;
    }
}
