package dev.magicshulkerboxes.mixin;

import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(CompoundContainer.class)
public interface CompoundContainerAccess {
    @Accessor("container1") Container msb$first();
    @Accessor("container2") Container msb$second();
}
