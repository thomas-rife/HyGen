package com.example.exampleplugin.hud;

import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import javax.annotation.Nonnull;

public final class HudUtils {
    private HudUtils() {
    }

    public static void clearHudSafely(@Nonnull Player player, @Nonnull PlayerRef playerRef) {
        player.getHudManager().setCustomHud(playerRef, new EmptyHud(playerRef));
    }

    private static final class EmptyHud extends CustomUIHud {
        private EmptyHud(@Nonnull PlayerRef playerRef) {
            super(playerRef);
        }

        @Override
        protected void build(@Nonnull UICommandBuilder ui) {
            // Intentionally empty to safely replace any active CustomUI HUD.
        }
    }
}
