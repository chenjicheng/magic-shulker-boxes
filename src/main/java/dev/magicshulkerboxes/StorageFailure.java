package dev.magicshulkerboxes;

import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.server.level.ServerPlayer;

/** Space failures are always visible, with one shared cooldown across storage and refill paths. */
public final class StorageFailure {
    private static final int NOTICE_INTERVAL_TICKS = 40;
    private static final Map<ServerPlayer, Integer> LAST_NOTICE = new WeakHashMap<>();

    private StorageFailure() {}

    public static void noSpace(ServerPlayer player) {
        int now = player.level().getServer().getTickCount();
        var previous = LAST_NOTICE.get(player);
        if (previous != null && now - previous < NOTICE_INTERVAL_TICKS) return;
        LAST_NOTICE.put(player, now);
        player.displayClientMessage(Messages.text(player.clientInformation().language(), "storage.no_space"), true);
    }
}
