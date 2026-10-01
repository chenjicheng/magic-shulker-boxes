package dev.magicshulkerboxes;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.file.Path;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

public final class MagicShulkerBoxes implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("magic_shulker_boxes");
    private static volatile ServerConfig config = new ServerConfig();
    private static final Map<MinecraftServer, PlayerSettingsStore> PLAYERS = new WeakHashMap<>();
    private static final Set<UUID> WARNED = new HashSet<>();

    public static ServerConfig config() {
        return config;
    }

    public static PlayerSettingsStore players(MinecraftServer server) {
        return PLAYERS.computeIfAbsent(server, key -> new PlayerSettingsStore(
                key.getWorldPath(LevelResource.ROOT).resolve("data/magic_shulker_boxes/players")));
    }

    public static StorageConfig configFor(ServerPlayer player) {
        try {
            return players(player.level().getServer()).resolve(player.getUUID(), config);
        } catch (IOException exception) {
            if (WARNED.add(player.getUUID())) LOGGER.error("Cannot load player settings / 无法加载玩家设置: {}", player.getUUID(), exception);
            return config;
        }
    }

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("magic_shulker_boxes.json");
    }

    public static void reload() throws IOException {
        var replacement = ConfigFile.load(configPath());
        config = replacement;
        PLAYERS.values().forEach(PlayerSettingsStore::clearCache);
        WARNED.clear();
    }

    public static void allowPlayerSettings(boolean allow) throws IOException {
        var replacement = ConfigFile.parseServer(ConfigFile.json(config));
        replacement.allowPlayerSettings = allow;
        replaceConfig(replacement);
    }

    /** Call on the server thread when a world is running. Publish only after the file is safely replaced. */
    public static void replaceConfig(ServerConfig replacement) throws IOException {
        var validated = ConfigFile.parseServer(ConfigFile.json(replacement));
        ConfigFile.writeServer(configPath(), validated);
        config = validated;
    }

    @Override
    public void onInitialize() {
        var path = configPath();
        try {
            config = ConfigFile.load(path);
        } catch (IOException exception) {
            // Invalid config must not silently enable behavior the administrator tried to turn off.
            config = new ServerConfig();
            config.pickupStorageEnabled = false;
            config.schematicRefill = false;
            config.ipnRefill = false;
            config.craftRefill = false;
            LOGGER.error("Cannot load {}. Automatic pickup storage and refill are disabled / 无法加载配置，自动入盒和取料已禁用。", path, exception);
        }
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            try { players(server).migrateExisting(); }
            catch (IOException exception) { LOGGER.error("Cannot scan player settings / 无法扫描玩家设置", exception); }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> { PLAYERS.remove(server); WARNED.clear(); });
        SettingsCommands.register();
        EditorNetwork.register();
        SettingsNetwork.register();
        RefillNetwork.register();
        RestockNetwork.register();
        EnderSourcesNetwork.register();
    }
}
