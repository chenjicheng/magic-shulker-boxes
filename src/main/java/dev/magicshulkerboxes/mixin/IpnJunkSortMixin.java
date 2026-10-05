package dev.magicshulkerboxes.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.magicshulkerboxes.client.IpnJunkSlots;
import kotlin.Unit;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.anti_ad.mc.ipnext.config.PostAction;
import org.anti_ad.mc.ipnext.item.rule.Rule;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(targets = "org.anti_ad.mc.ipnext.inventory.InnerActions", remap = false)
abstract class IpnJunkSortMixin {
    @WrapMethod(method = "doSort")
    private Unit msb$reserveJunkSlots(Rule rule, PostAction post, boolean container, AbstractContainerMenu menu, Operation<Unit> original) {
        return IpnJunkSlots.sorting(() -> original.call(rule, post, container, menu));
    }
}
