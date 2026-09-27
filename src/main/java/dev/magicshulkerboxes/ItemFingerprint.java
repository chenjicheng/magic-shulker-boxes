package dev.magicshulkerboxes;

import com.google.common.hash.Hashing;
import net.minecraft.core.HolderLookup;
import net.minecraft.util.HashOps;
import net.minecraft.world.item.ItemStack;

/** Canonical codec hash: registry names and component values, never Java object identity. */
public final class ItemFingerprint {
    private static final HashOps HASH_OPS = new HashOps(Hashing.sha256());
    private ItemFingerprint() {}

    public static String of(ItemStack stack, HolderLookup.Provider registries) {
        if (stack.isEmpty()) return "";
        // Persistent codecs omit transient patches. Refuse these instead of silently ignoring identity data.
        if (stack.getComponentsPatch().entrySet().stream().anyMatch(entry -> entry.getKey().isTransient())) return "";
        // Counts may change during ordinary pickup; only the requested material's identity matters.
        return ItemStack.CODEC.encodeStart(registries.createSerializationContext(HASH_OPS), stack.copyWithCount(1))
                .result().map(Object::toString).orElse("");
    }
}
