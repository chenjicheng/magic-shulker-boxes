package dev.magicshulkerboxes.client;

import dev.magicshulkerboxes.*;
import java.io.IOException;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

/** Litematica continues its normal pick/place on the next held-use tick after the server sync. */
public final class SchematicRefillClient {
    private static boolean handling, handled;
    private static long nextRequest, nextNotice;
    private static StorageConfig settings;
    private SchematicRefillClient() {}
    public static void begin() { handling = true; handled = false; }
    public static boolean end() { handling = false; return handled; }
    public static void invalidateSettings() { settings = null; }
    public static void register() {
        ClientPlayConnectionEvents.INIT.register((handler, client) -> reset());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
    }
    private static void reset() { handling = handled = false; nextRequest = nextNotice = 0; settings = null; }
    private static StorageConfig settings() {
        if (settings != null) return settings;
        settings = new StorageConfig();
        var defaults = ClientSettings.defaults();
        if (defaults != null) settings = ConfigFile.apply(settings, defaults);
        if (ClientSettings.session.mode() == SettingsSession.Mode.ALLOWED || defaults == null) {
            try { settings = ConfigFile.apply(settings, ConfigFile.readPreferences(MagicShulkerBoxesClient.path())); }
            catch (IOException exception) { settings.schematicRefill = false; ClientSettings.failure(exception); }
        }
        return settings;
    }
    private static void notice(String reason) {
        var mc = Minecraft.getInstance();
        if (mc.player != null && settings().refillFailureMessages && System.nanoTime() >= nextNotice) {
            nextNotice = System.nanoTime() + 2_000_000_000L;
            mc.player.displayClientMessage(ClientSettings.text("refill." + reason), true);
        }
    }
    public static boolean tryRefill(ItemStack wanted, Minecraft mc) {
        if (!handling || wanted.isEmpty() || mc.player == null || mc.player.isSpectator() || mc.player.isCreative()
                || mc.player.containerMenu != mc.player.inventoryMenu || !mc.player.inventoryMenu.getCarried().isEmpty()) return false;
        var inventory = mc.player.getInventory();
        if (inventory.findSlotMatchingItem(wanted) >= 0 || ItemStack.isSameItemSameComponents(mc.player.getOffhandItem(), wanted)) return false;
        var config = settings();
        if (!config.schematicRefill) { notice("disabled"); handled = true; return true; }
        if (!ClientPlayNetworking.canSend(RefillNetwork.Request.ID)) { notice("unsupported"); return false; }
        handled = true;
        if (System.nanoTime() < nextRequest) return true;
        nextRequest = System.nanoTime() + 500_000_000L;
        var match = RefillSearch.find(inventory, wanted, config);
        if (match != null) {
            ClientPlayNetworking.send(new RefillNetwork.Request(match.boxSlot(), match.contentSlot(), BuiltInRegistries.ITEM.getKey(wanted.getItem()).toString()));
            return true;
        }
        notice("missing");
        return true;
    }
}
