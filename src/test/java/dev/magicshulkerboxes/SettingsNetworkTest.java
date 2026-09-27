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
            var state = new EditorNetwork.State(true, "{\"enabled\":false}");
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
            var preferences = new SettingsNetwork.Preferences("{\"enabled\":false}");
            SettingsNetwork.Preferences.CODEC.encode(buffer, preferences);
            assertEquals(preferences, SettingsNetwork.Preferences.CODEC.decode(buffer));
            SettingsNetwork.Policy.CODEC.encode(buffer, new SettingsNetwork.Policy(true));
            assertTrue(SettingsNetwork.Policy.CODEC.decode(buffer).allowed());
            assertThrows(io.netty.handler.codec.EncoderException.class,
                    () -> SettingsNetwork.Preferences.CODEC.encode(buffer, new SettingsNetwork.Preferences("x".repeat(4097))));
        } finally { buffer.release(); }
    }
}
