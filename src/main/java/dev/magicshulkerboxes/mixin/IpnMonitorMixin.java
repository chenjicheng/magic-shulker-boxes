package dev.magicshulkerboxes.mixin;

import dev.magicshulkerboxes.client.IpnRefillClient;
import org.anti_ad.mc.ipnext.event.autorefill.AutoRefillHandler$ItemSlotMonitor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "org.anti_ad.mc.ipnext.event.autorefill.AutoRefillHandler$ItemSlotMonitor", remap = false)
public abstract class IpnMonitorMixin {
    @Inject(method = "checkHandle", at = @At("HEAD"), remap = false)
    private void msb$cancelObsoleteWait(CallbackInfo ci) {
        var monitor = (AutoRefillHandler$ItemSlotMonitor) (Object) this;
        if (!monitor.getShouldHandle()) IpnRefillClient.cancel(monitor);
    }
    @Inject(method = "checkHandle", at = @At(value = "INVOKE",
            target = "Lorg/anti_ad/mc/ipnext/event/autorefill/AutoRefillHandler$ItemSlotMonitor;handle()V"), cancellable = true, remap = false)
    private void msb$awaitServerExtraction(CallbackInfo ci) {
        if (IpnRefillClient.beforeHandle((AutoRefillHandler$ItemSlotMonitor) (Object) this)) ci.cancel();
    }
}
