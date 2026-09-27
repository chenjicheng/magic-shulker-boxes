package dev.magicshulkerboxes;

import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ItemFingerprintTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
    @Test void fingerprintIgnoresCountButIncludesAddedRemovedAndChangedComponents() {
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        var plain = new ItemStack(Items.STONE);
        var expected = ItemFingerprint.of(plain, registries);
        assertEquals(64, expected.length());
        assertEquals(expected, ItemFingerprint.of(plain.copyWithCount(64), registries));
        var named = plain.copy(); named.set(DataComponents.CUSTOM_NAME, Component.literal("Reserved"));
        var namedHash = ItemFingerprint.of(named, registries);
        assertNotEquals(expected, namedHash);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("Changed"));
        assertNotEquals(namedHash, ItemFingerprint.of(named, registries));
        var removed = plain.copy(); removed.remove(DataComponents.ITEM_NAME);
        assertNotEquals(expected, ItemFingerprint.of(removed, registries));
        assertNotEquals(expected, ItemFingerprint.of(new ItemStack(Items.DIRT), registries));
    }

    @Test void componentInsertionOrderDoesNotChangeFingerprint() {
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        var first = new ItemStack(Items.STONE);
        var second = new ItemStack(Items.STONE);
        first.set(DataComponents.CUSTOM_NAME, Component.literal("Same")); first.set(DataComponents.MAX_STACK_SIZE, 32);
        second.set(DataComponents.MAX_STACK_SIZE, 32); second.set(DataComponents.CUSTOM_NAME, Component.literal("Same"));
        assertEquals(ItemFingerprint.of(first, registries), ItemFingerprint.of(second, registries));
    }

    @Test void transientComponentsCannotBeSilentlyOmitted() {
        var stack = new ItemStack(Items.STONE);
        var transientType = net.minecraft.core.component.DataComponentType.<Integer>builder()
                .networkSynchronized(net.minecraft.network.codec.ByteBufCodecs.INT).build();
        stack.set(transientType, 1);
        assertEquals("", ItemFingerprint.of(stack, RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY)));
    }

    @Test void requestRoundTripsBoundedFingerprintAndUsesNewProtocol() {
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            var request = new RefillNetwork.Request(9, 0, "minecraft:stone", "a".repeat(64));
            RefillNetwork.Request.CODEC.encode(buffer, request);
            assertEquals(request, RefillNetwork.Request.CODEC.decode(buffer));
            assertEquals("refill_v2", RefillNetwork.Request.ID.id().getPath());
            assertThrows(io.netty.handler.codec.EncoderException.class, () -> RefillNetwork.Request.CODEC.encode(buffer,
                    new RefillNetwork.Request(9, 0, "minecraft:stone", "a".repeat(65))));
        } finally { buffer.release(); }
    }
}
