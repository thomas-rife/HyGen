package com.example.exampleplugin.hud;

import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import javax.annotation.Nonnull;

public class VictoryHud extends CustomUIHud {
    private boolean visible;

    public VictoryHud(@Nonnull PlayerRef playerRef) {
        super(playerRef);
    }

    @Override
    protected void build(@Nonnull UICommandBuilder ui) {
        if (!this.visible) {
            return;
        }
        ui.append("VictoryHud.ui");
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
    }
}
