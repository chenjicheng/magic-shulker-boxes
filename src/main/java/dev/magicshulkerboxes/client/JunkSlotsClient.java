package dev.magicshulkerboxes.client;

import dev.magicshulkerboxes.JunkSlots;
import dev.magicshulkerboxes.JunkSlotsNetwork;
import dev.magicshulkerboxes.ShulkerStorage;
import dev.magicshulkerboxes.mixin.JunkSlotScreenAccess;
import java.util.List;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

/** Inventory-only input and rendering; role persistence and item ownership remain on the server. */
public final class JunkSlotsClient {
    private static final JunkSlotSession SESSION = new JunkSlotSession();
    private static final JunkSlotGesture GESTURE = new JunkSlotGesture();
    private static final int ACTIVE = 0xFF65D9CB, INACTIVE = 0xFF92999F, PENDING = 0xFFF3CF69;
    private static KeyMapping binding;
    private static AbstractContainerScreen<?> heldScreen;
    private static ClientPacketListener heldConnection;
    private static int nextRequest;
    private static long deadline, nextSave;
    private JunkSlotsClient() {}

    public static long confirmedMask() { return SESSION.known() ? SESSION.confirmed() : 0; }
    public static boolean ready() { return SESSION.known(); }
    public static KeyMapping binding() { return binding; }

    public static void register() {
        var category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("magic_shulker_boxes", "slots"));
        binding = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.magic_shulker_boxes.junk_slots",
                GLFW.GLFW_KEY_F9, category));
        ClientPlayConnectionEvents.INIT.register((handler, client) -> reset());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
        ClientPlayNetworking.registerGlobalReceiver(JunkSlotsNetwork.State.ID, (state, context) -> {
            if (!SESSION.receive(state)) return;
            nextSave = System.nanoTime() + 150_000_000L;
            if (state.status() == JunkSlotsNetwork.STALE) notice("changed");
            else if (state.status() == JunkSlotsNetwork.FAILED || state.status() == JunkSlotsNetwork.INVALID) notice("failed");
        });
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            // Fabric resets per-screen events on every init, including resource reload and window resizing.
            if (!(screen instanceof AbstractContainerScreen<?> inventory)) return;
            ScreenKeyboardEvents.allowKeyPress(screen).register((current, event) -> {
                if (!binding.matches(event) || current.getFocused() instanceof EditBox) return true;
                begin(inventory); return false;
            });
            ScreenKeyboardEvents.allowKeyRelease(screen).register((current, event) -> {
                if (!binding.matches(event)) return true;
                finish(); return false;
            });
            ScreenMouseEvents.allowMouseClick(screen).register((current, event) -> {
                if (binding.matchesMouse(event)) { begin(inventory); return false; }
                return heldScreen != inventory;
            });
            ScreenMouseEvents.allowMouseRelease(screen).register((current, event) -> {
                if (!binding.matchesMouse(event)) return true;
                finish(); return false;
            });
            ScreenMouseEvents.allowMouseDrag(screen).register((current, event, dx, dy) -> heldScreen != inventory);
            ScreenEvents.remove(screen).register(current -> { if (heldScreen == inventory) finish(); });
        });
        ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> {
            var client = Minecraft.getInstance();
            if (!(client.screen instanceof AbstractContainerScreen<?> screen)) return;
            var slot = ((JunkSlotScreenAccess) screen).msb$hoveredSlot();
            int index = index(slot);
            if (!JunkSlots.selected(visibleMask() | SESSION.confirmed(), index) || !ItemStack.isSameItemSameComponents(stack, slot.getItem())) return;
            lines.addAll(tooltip(slot));
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (binding.consumeClick()) { /* Screen events exclusively own this binding. */ }
            if (client.player == null || !ClientPlayNetworking.canSend(JunkSlotsNetwork.Save.ID)) return;
            long now = System.nanoTime();
            if (SESSION.pending() && now > deadline) {
                ClientPlayNetworking.send(new JunkSlotsNetwork.Query(SESSION.request()));
                deadline = now + 5_000_000_000L;
            } else if (SESSION.dirty() && !SESSION.pending() && now >= nextSave && heldScreen == null) {
                nextRequest = nextRequest == Integer.MAX_VALUE ? 1 : nextRequest + 1;
                SESSION.requesting(nextRequest);
                deadline = now + 5_000_000_000L;
                ClientPlayNetworking.send(new JunkSlotsNetwork.Save(nextRequest, SESSION.revision(), SESSION.desired()));
            }
        });
    }

    private static void reset() {
        SESSION.reset(); heldScreen = null; heldConnection = null; deadline = nextSave = 0;
    }
    private static void begin(AbstractContainerScreen<?> screen) {
        if (heldScreen != null) return;
        if (!SESSION.known()) { notice(ClientPlayNetworking.canSend(JunkSlotsNetwork.Save.ID) ? "unavailable" : "unsupported"); return; }
        heldScreen = screen; heldConnection = Minecraft.getInstance().getConnection();
        GESTURE.begin(SESSION.desired());
        GESTURE.visit(index(((JunkSlotScreenAccess) screen).msb$hoveredSlot()));
    }
    private static void finish() {
        if (heldScreen == null) return;
        if (heldConnection == Minecraft.getInstance().getConnection() && SESSION.known()) SESSION.choose(GESTURE.mask());
        heldScreen = null; heldConnection = null;
    }
    private static int index(Slot slot) {
        var client = Minecraft.getInstance();
        if (slot == null || client.player == null || slot.container != client.player.getInventory()) return -1;
        int index = slot.getContainerSlot();
        return index >= 0 && index < JunkSlots.SLOT_COUNT ? index : -1;
    }
    private static long visibleMask() { return heldScreen != null ? GESTURE.mask() : SESSION.desired(); }

    /** Called after screen contents, before vanilla renders deferred tooltips on their own stratum. */
    public static void renderBeforeTooltip(AbstractContainerScreen<?> screen, GuiGraphics graphics, int mouseX, int mouseY) {
        var access = (JunkSlotScreenAccess) screen;
        if (heldScreen == screen) GESTURE.visit(index(access.msb$hoveredSlot()));
        long mask = visibleMask();
        for (var slot : screen.getMenu().slots) {
            int index = index(slot);
            if (!JunkSlots.selected(mask | SESSION.confirmed(), index)) continue;
            int x = access.msb$leftPos() + slot.x, y = access.msb$topPos() + slot.y;
            boolean tentative = heldScreen == screen || JunkSlots.selected(mask ^ SESSION.confirmed(), index);
            int color = tentative ? PENDING : ShulkerStorage.isShulker(slot.getItem()) ? ACTIVE : INACTIVE;
            // A seven-pixel box in the top-left avoids counts, durability bars and IPN's bottom-right lock sprite.
            graphics.fill(x - 1, y - 1, x + 6, y + 5, 0xFF162129);
            graphics.fill(x, y, x + 5, y + 1, color);
            graphics.fill(x, y + 1, x + 1, y + 4, color);
            graphics.fill(x + 4, y + 1, x + 5, y + 4, color);
            graphics.fill(x, y + 3, x + 5, y + 4, color);
            graphics.fill(x + 1, y + 1, x + 4, y + 2, color);
            graphics.fill(x + 2, y + 1, x + 3, y + 3, 0xFF162129);
            if (!JunkSlots.selected(mask, index)) graphics.fill(x, y + 2, x + 5, y + 3, PENDING);
            if (heldScreen == screen) graphics.renderOutline(x - 1, y - 1, 18, 18, 0x8865D9CB);
        }
        var hover = access.msb$hoveredSlot();
        if (hover != null && hover.getItem().isEmpty() && JunkSlots.selected(mask | SESSION.confirmed(), index(hover)))
            graphics.setComponentTooltipForNextFrame(Minecraft.getInstance().font, tooltip(hover), mouseX, mouseY);
    }
    private static List<Component> tooltip(Slot slot) {
        int index = index(slot);
        boolean changing = JunkSlots.selected(visibleMask() ^ SESSION.confirmed(), index);
        String status = changing ? (JunkSlots.selected(visibleMask(), index) ? "pending" : "removing")
                : ShulkerStorage.isShulker(slot.getItem()) ? "active" : "empty";
        return List.of(ClientSettings.text("junk.title"), ClientSettings.text("junk." + status),
                ClientSettings.text("junk.hint", binding.getTranslatedKeyMessage()));
    }
    private static void notice(String key) {
        var player = Minecraft.getInstance().player;
        if (player != null) player.displayClientMessage(ClientSettings.text("junk." + key), true);
    }
}
