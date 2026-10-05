package dev.magicshulkerboxes.mixin;

import dev.magicshulkerboxes.client.JunkSlotsClient;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Screen.class)
public abstract class JunkSlotTooltipMixin {
    // Fabric afterRender runs after deferred tooltips. Use the native boundary so every
    // container subclass draws markers above its contents but below the tooltip stratum.
    @Inject(method = "renderWithTooltipAndSubtitles", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphics;renderDeferredElements()V"))
    private void msb$renderJunkSlotsBeforeTooltip(GuiGraphics graphics, int mouseX, int mouseY,
            float delta, CallbackInfo callback) {
        if ((Object) this instanceof AbstractContainerScreen<?> screen)
            JunkSlotsClient.renderBeforeTooltip(screen, graphics, mouseX, mouseY);
    }
}
