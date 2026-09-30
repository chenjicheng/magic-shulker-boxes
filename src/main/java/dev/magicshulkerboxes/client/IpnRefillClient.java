package dev.magicshulkerboxes.client;

import dev.magicshulkerboxes.ItemFingerprint;
import dev.magicshulkerboxes.RestockNetwork;
import java.util.Map;
import java.util.WeakHashMap;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import org.anti_ad.mc.ipnext.event.autorefill.AutoRefillHandler$ItemSlotMonitor;

/** Pause IPN's existing monitor until server inventory synchronization makes its chosen item visible. */
public final class IpnRefillClient {
    private static final long REQUEST_INTERVAL = 500_000_000L;
    private static final long SYNC_TIMEOUT = 5_000_000_000L;
    private static final Map<AutoRefillHandler$ItemSlotMonitor, Pending> PENDING = new WeakHashMap<>();
    private static int nextId;
    private static long nextRequest;
    private IpnRefillClient() {}
    private static final class Pending {
        final int id;
        final long deadline;
        boolean failed;
        Pending(int id, long deadline) { this.id = id; this.deadline = deadline; }
    }

    public static void register() {
        ClientPlayConnectionEvents.INIT.register((handler, client) -> reset());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
        ClientPlayNetworking.registerGlobalReceiver(RestockNetwork.Result.ID, (result, context) -> {
            for (var pending : PENDING.values()) if (pending.id == result.request()) pending.failed = !result.success();
        });
    }
    private static void reset() { PENDING.clear(); nextId = 0; nextRequest = 0; }
    public static void cancel(AutoRefillHandler$ItemSlotMonitor monitor) { PENDING.remove(monitor); }

    /** Called after IPN's own trigger, disabled-slot checks and configured wait ticks, immediately before handle(). */
    public static boolean beforeHandle(AutoRefillHandler$ItemSlotMonitor monitor) {
        var client = Minecraft.getInstance();
        int menuSlot = monitor.getStoredSlotId();
        int targetSlot = menuSlot >= 36 && menuSlot <= 44 ? menuSlot - 36 : menuSlot == 45 ? 40 : -1;
        if (targetSlot < 0 || client.player == null || client.player.isCreative() || client.player.isSpectator()
                || client.player.containerMenu != client.player.inventoryMenu || !client.player.inventoryMenu.getCarried().isEmpty()
                || !RefillSettings.get().ipnRefill || !ClientPlayNetworking.canSend(RestockNetwork.Request.ID)) {
            PENDING.remove(monitor); return false;
        }
        // Backpack candidates always win. IPN will perform its normal clicks and notifications itself.
        if (IpnCandidates.finder().msb$findCorrespondingSlot(monitor.getCheckingItem(), monitor.getCurrentItem()) != null) {
            PENDING.remove(monitor); return false;
        }
        long now = System.nanoTime();
        var pending = PENDING.get(monitor);
        if (pending != null) {
            if (pending.failed || now >= pending.deadline) { PENDING.remove(monitor); return false; }
            return true;
        }
        var source = IpnCandidates.find(monitor.getCheckingItem(), monitor.getCurrentItem());
        if (source == null) return false;
        if (now < nextRequest) return true;
        var target = client.player.getInventory().getItem(targetSlot);
        var sourceFingerprint = ItemFingerprint.of(source.stack(), client.player.registryAccess());
        var targetFingerprint = ItemFingerprint.of(target, client.player.registryAccess());
        if (sourceFingerprint.isEmpty() || (!target.isEmpty() && targetFingerprint.isEmpty())) return false;
        if (++nextId <= 0) nextId = 1;
        PENDING.put(monitor, new Pending(nextId, now + SYNC_TIMEOUT));
        nextRequest = now + REQUEST_INTERVAL;
        ClientPlayNetworking.send(new RestockNetwork.Request(nextId, source.boxSlot(), source.contentSlot(), source.boxCount(),
                source.stack().getCount(), targetSlot, target.getCount(), source.eligibleSlots(), sourceFingerprint, targetFingerprint));
        return true;
    }
}
