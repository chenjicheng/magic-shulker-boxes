package dev.magicshulkerboxes.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;

public final class ModMenuIntegration implements ModMenuApi {
    @Override public ConfigScreenFactory<?> getModConfigScreenFactory() {
        // Keep the YACL class behind this check, so installing only Mod Menu is safe.
        return parent -> FabricLoader.getInstance().isModLoaded("yet_another_config_lib_v3")
                ? SettingsGui.personal(parent)
                : new ConfirmScreen(ignored -> Minecraft.getInstance().setScreen(parent),
                        ClientSettings.text("title"), ClientSettings.text("gui.missing_yacl"));
    }
}
