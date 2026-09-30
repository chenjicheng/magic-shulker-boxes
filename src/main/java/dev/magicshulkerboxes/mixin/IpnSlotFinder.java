package dev.magicshulkerboxes.mixin;

import org.anti_ad.mc.ipnext.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Invoker;

@Pseudo
@Mixin(targets = "org.anti_ad.mc.ipnext.event.autorefill.AutoRefillHandler$ItemSlotMonitor$Companion", remap = false)
public interface IpnSlotFinder {
    @Invoker(value = "findCorrespondingSlot", remap = false)
    Integer msb$findCorrespondingSlot(ItemStack checkingItem, ItemStack currentItem);
}
