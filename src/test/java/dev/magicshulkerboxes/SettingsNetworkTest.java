package dev.magicshulkerboxes;

import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SettingsNetworkTest {
    @Test
    void editorMessagesRoundTripAndRejectOversizedPreferences() {
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            var state = new EditorNetwork.State(true, "{\"pickupStorageEnabled\":false}");
            EditorNetwork.State.CODEC.encode(buffer, state);
            assertEquals(state, EditorNetwork.State.CODEC.decode(buffer));
            var save = new EditorNetwork.Save(12, "{}");
            EditorNetwork.Save.CODEC.encode(buffer, save);
            assertEquals(save, EditorNetwork.Save.CODEC.decode(buffer));
            var result = new EditorNetwork.Result(12, EditorNetwork.SAVED, "{}");
            EditorNetwork.Result.CODEC.encode(buffer, result);
            assertEquals(result, EditorNetwork.Result.CODEC.decode(buffer));
            var query = new EditorNetwork.Query(15);
            EditorNetwork.Query.CODEC.encode(buffer, query);
            assertEquals(query, EditorNetwork.Query.CODEC.decode(buffer));
            assertThrows(io.netty.handler.codec.EncoderException.class,
                    () -> EditorNetwork.Save.CODEC.encode(buffer, new EditorNetwork.Save(13, "x".repeat(4097))));
        } finally { buffer.release(); }
    }
    @Test
    void boundedWireFormatRoundTripsPreferencesAndPolicy() {
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            var preferences = new SettingsNetwork.Preferences("{\"pickupStorageEnabled\":false}");
            SettingsNetwork.Preferences.CODEC.encode(buffer, preferences);
            assertEquals(preferences, SettingsNetwork.Preferences.CODEC.decode(buffer));
            SettingsNetwork.Policy.CODEC.encode(buffer, new SettingsNetwork.Policy(true));
            assertTrue(SettingsNetwork.Policy.CODEC.decode(buffer).allowed());
            assertThrows(io.netty.handler.codec.EncoderException.class,
                    () -> SettingsNetwork.Preferences.CODEC.encode(buffer, new SettingsNetwork.Preferences("x".repeat(4097))));
        } finally { buffer.release(); }
    }
    @Test void restockSnapshotsRoundTripAndFingerprintsStayBounded() {
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            var request = new RestockNetwork.Request(3, 100, -1, 1, 1, 40, 0, 3,
                    "a".repeat(64), "", "b".repeat(64));
            RestockNetwork.Request.CODEC.encode(buffer, request);
            assertEquals(request, RestockNetwork.Request.CODEC.decode(buffer));
            assertEquals("restock_v2", RestockNetwork.Request.ID.id().getPath());
            assertThrows(io.netty.handler.codec.EncoderException.class, () -> RestockNetwork.Request.CODEC.encode(buffer,
                    new RestockNetwork.Request(3, 100, -1, 1, 1, 40, 0, 3, "a".repeat(64), "", "b".repeat(65))));
            buffer.clear();
            var empty = new EnderSourcesNetwork.Snapshot(java.util.Collections.nCopies(27, net.minecraft.world.item.ItemStack.EMPTY));
            EnderSourcesNetwork.Snapshot.CODEC.encode(buffer, empty);
            assertEquals(27, EnderSourcesNetwork.Snapshot.CODEC.decode(buffer).items().size());
            assertThrows(IllegalArgumentException.class, () -> EnderSourcesNetwork.Snapshot.CODEC.encode(buffer,
                    new EnderSourcesNetwork.Snapshot(java.util.Collections.nCopies(28, net.minecraft.world.item.ItemStack.EMPTY))));
        } finally { buffer.release(); }
    }

}
