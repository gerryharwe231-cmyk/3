package com.slopeconnector.surface.orientation;

import net.minecraft.server.network.ServerPlayerEntity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Per-player toggle for view-directed block placement. */
public final class ArcPlacementOrientationSettings {
    private static final Map<UUID, Boolean> ENABLED = new ConcurrentHashMap<>();

    private ArcPlacementOrientationSettings() {}

    public static boolean enabled(ServerPlayerEntity player) {
        return player != null && ENABLED.getOrDefault(player.getUuid(), false);
    }

    public static boolean set(ServerPlayerEntity player, boolean enabled) {
        if (player == null) return false;
        if (enabled) ENABLED.put(player.getUuid(), true);
        else ENABLED.remove(player.getUuid());
        PlacedOrientationService.sendSetting(player, enabled);
        return enabled;
    }

    public static boolean toggle(ServerPlayerEntity player) {
        return set(player, !enabled(player));
    }
}
