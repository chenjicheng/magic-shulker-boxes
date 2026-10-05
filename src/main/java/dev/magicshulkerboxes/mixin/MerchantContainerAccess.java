package dev.magicshulkerboxes.mixin;

import net.minecraft.world.inventory.MerchantContainer;
import net.minecraft.world.item.trading.Merchant;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(MerchantContainer.class)
public interface MerchantContainerAccess {
    @Accessor("merchant") Merchant msb$merchant();
}
