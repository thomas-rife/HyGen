package com.example.exampleplugin.npc;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;

public final class BattleheartCameraPreferences {
    private static final ConcurrentHashMap.KeySetView<UUID, Boolean> THIRD_PERSON_CAMERA_ENABLED = ConcurrentHashMap.newKeySet();

    private BattleheartCameraPreferences() {
    }

    public static boolean isThirdPersonCameraEnabled(@Nonnull UUID playerUuid) {
        return THIRD_PERSON_CAMERA_ENABLED.contains(playerUuid);
    }

    public static boolean toggleThirdPersonCamera(@Nonnull UUID playerUuid) {
        if (THIRD_PERSON_CAMERA_ENABLED.remove(playerUuid)) {
            return false;
        }
        THIRD_PERSON_CAMERA_ENABLED.add(playerUuid);
        return true;
    }

    public static boolean isOverviewCameraEnabled(@Nonnull UUID playerUuid) {
        return !isThirdPersonCameraEnabled(playerUuid);
    }
}
