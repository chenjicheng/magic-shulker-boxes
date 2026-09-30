package dev.magicshulkerboxes.mixin;

import dev.magicshulkerboxes.client.IpnCandidates;
import kotlin.collections.IndexedValue;
import org.anti_ad.mc.ipnext.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Pseudo
@Mixin(targets = "org.anti_ad.mc.ipnext.event.autorefill.AutoRefillHandler$ItemSlotMonitor$Companion", remap = false)
public abstract class IpnCandidatesMixin {
    @ModifyArg(method = "findCorrespondingSlot", at = @At(value = "INVOKE",
            target = "Lkotlin/collections/CollectionsKt;asSequence(Ljava/lang/Iterable;)Lkotlin/sequences/Sequence;"), index = 0, remap = false)
    private Iterable<IndexedValue<ItemStack>> msb$boxCandidates(Iterable<IndexedValue<ItemStack>> candidates) {
        return IpnCandidates.extend(candidates);
    }
}
