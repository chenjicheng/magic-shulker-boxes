package dev.magicshulkerboxes.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.magicshulkerboxes.FakePlayerRefill;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

@Pseudo
@Mixin(targets = "carpet.patches.EntityPlayerMPFake", remap = false)
public abstract class CarpetFakePlayerMixin {
    // Carpet overrides the mapped vanilla tick: named in development, intermediary in its published JAR.
    @WrapMethod(method = {"tick", "method_5773"}, remap = false)
    private void msb$refillHands(Operation<Void> original) {
        FakePlayerRefill.tick((ServerPlayer) (Object) this, () -> original.call());
    }
}
