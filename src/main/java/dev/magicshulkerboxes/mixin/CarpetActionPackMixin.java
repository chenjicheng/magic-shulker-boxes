package dev.magicshulkerboxes.mixin;

import dev.magicshulkerboxes.FakePlayerRefill;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "carpet.helpers.EntityPlayerActionPack", remap = false)
public abstract class CarpetActionPackMixin {
    @Shadow @Final private ServerPlayer player;

    @Inject(method = "drop", at = @At("HEAD"), remap = false)
    private void msb$keepExplicitDrop(CallbackInfo ci) { FakePlayerRefill.dropping(player); }
}
