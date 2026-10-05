package dev.magicshulkerboxes.mixin;

import dev.magicshulkerboxes.client.IpnJunkSlots;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "org.anti_ad.mc.ipnext.event.LockSlotsHandler", remap = false)
abstract class IpnJunkLocksMixin {
    @Inject(method = "getLockedInvSlots", at = @At("RETURN"), cancellable = true)
    private void msb$sortingLocks(CallbackInfoReturnable<Iterable<Integer>> result) {
        result.setReturnValue(IpnJunkSlots.locks(result.getReturnValue()));
    }
}
