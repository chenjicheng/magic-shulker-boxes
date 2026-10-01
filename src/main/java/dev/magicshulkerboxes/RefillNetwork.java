package dev.magicshulkerboxes;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.HashSet;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.GameType;

/** Slot indices, an item ID and a bounded fingerprint cross the wire; extracted data is server-owned. */
public final class RefillNetwork {
    private RefillNetwork() {}
    private static final Map<ServerPlayer, Integer> REQUESTS = new WeakHashMap<>();
    private static final Map<ServerPlayer, Integer> NOTICES = new WeakHashMap<>();
    public record Request(int boxSlot, int contentSlot, String item, String fingerprint) implements CustomPacketPayload {
        public static final Type<Request> ID = new Type<>(Identifier.fromNamespaceAndPath("magic_shulker_boxes", "refill_v4"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Request::boxSlot, ByteBufCodecs.VAR_INT, Request::contentSlot,
                ByteBufCodecs.stringUtf8(256), Request::item,
                ByteBufCodecs.stringUtf8(64), Request::fingerprint, Request::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }
    public static void register() {
        PayloadTypeRegistry.playC2S().register(Request.ID, Request.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(Request.ID, (request, context) -> accept(context.player(), request));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> { REQUESTS.clear(); NOTICES.clear(); });
    }

    public static int accept(ServerPlayer player, Request request) {
        int now = player.level().getServer().getTickCount();
        var last = REQUESTS.get(player);
        if (last != null && now - last < 10) return 0;
        REQUESTS.put(player, now);
        var config = MagicShulkerBoxes.configFor(player);
        if (!config.schematicRefill) {
            failure(player, config, "disabled", now); return 0;
        }
        var mode = player.gameMode.getGameModeForPlayer();
        if (!player.isAlive() || (mode != GameType.SURVIVAL && mode != GameType.ADVENTURE) || player.containerMenu != player.inventoryMenu
                || !player.inventoryMenu.getCarried().isEmpty()) return 0;
        var id = Identifier.tryParse(request.item());
        if (id == null) return 0;
        var item = BuiltInRegistries.ITEM.getOptional(id).orElse(null);
        var inventory = player.getInventory();
        var sources = RefillSources.of(player, config);
        boolean ender = config.enderChestRefill && RefillSources.isEnder(sources, request.boxSlot());
        if (item == null || request.boxSlot() < 0 || request.boxSlot() >= sources.getContainerSize()
                || (request.boxSlot() >= 36 && !ender && !(config.includeOffhand && request.boxSlot() == 40))) return 0;
        var contents = NonNullList.withSize(27, ItemStack.EMPTY);
        var wanted = RefillSources.item(sources, request.boxSlot(), request.contentSlot());
        if (wanted.isEmpty() || !wanted.is(item) || request.fingerprint().length() != 64
                || !request.fingerprint().equals(ItemFingerprint.of(wanted, player.registryAccess()))) {
            failure(player, config, "changed", now); return 0;
        }
        // A delayed or repeated request must not keep pulling stacks after the first refill arrived.
        if (inventory.findSlotMatchingItem(wanted) >= 0 || ItemStack.isSameItemSameComponents(player.getOffhandItem(), wanted)) return 0;
        int moved = 0;
        // Enforce the same least-filled-first order even for stale or manually selected client slots.
        // A blocked source (for example a stacked box with no split slot) must not hide a usable box.
        for (int pass = 0; pass < (config.enderChestRefill ? 2 : 1) && moved == 0; pass++) {
            if (pass == 1 && config.enderChestRefill && sources instanceof RefillSources.View) {
                for (int slot = RefillSources.ENDER_START; slot < sources.getContainerSize() && moved == 0; slot++) {
                    if (ItemStack.isSameItemSameComponents(RefillSources.item(sources, slot, -1), wanted))
                        moved = ShulkerRefill.take(sources, slot, -1, item, config);
                }
            }
            for (int slot : BoxOrder.emptiestFirst(sources, config)) {
                if (RefillSources.isEnder(sources, slot) != (pass == 1)) continue;
                if (moved > 0) break;
                var alternative = sources.getItem(slot);
                if (!ShulkerStorage.isShulker(alternative)) continue;
                var data = alternative.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
                if (data.stream().count() > 27) continue;
                data.copyInto(contents);
                // Equal components AND counts have identical capacity outcomes in the same box.
                // Different counts remain candidates: extracting a smaller stack may free a whole slot.
                var triedCounts = new HashSet<Integer>();
                for (int inner = 0; inner < 27 && moved == 0; inner++) {
                    if (ItemStack.isSameItemSameComponents(contents.get(inner), wanted)
                            && triedCounts.add(contents.get(inner).getCount())) {
                        moved = ShulkerRefill.take(sources, slot, inner, item, config);
                    }
                }
            }
        }
        if (moved > 0) player.inventoryMenu.broadcastChanges();
        else failure(player, config, "space", now);
        return moved;
    }

    private static void failure(ServerPlayer player, StorageConfig config, String reason, int now) {
        var last = NOTICES.get(player);
        if (!config.refillFailureMessages || (last != null && now - last < 40)) return;
        NOTICES.put(player, now);
        player.displayClientMessage(Messages.text(player.clientInformation().language(), "refill." + reason), true);
    }
}
