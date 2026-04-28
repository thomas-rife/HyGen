package com.example.exampleplugin.hud;

import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;

public class LevelEditorHud extends CustomUIHud {
    private boolean visible;
    @Nonnull
    private List<String> leftLines = List.of();
    @Nonnull
    private List<String> rightLines = List.of();

    public LevelEditorHud(@Nonnull PlayerRef playerRef) {
        super(playerRef);
    }

    @Override
    protected void build(@Nonnull UICommandBuilder ui) {
        if (!this.visible) {
            return;
        }
        ui.append("LevelEditorHud.ui");
        writeLines(ui);
    }

    public void refresh() {
        UICommandBuilder ui = new UICommandBuilder();
        writeLines(ui);
        update(false, ui);
    }

    private void writeLines(@Nonnull UICommandBuilder ui) {
        List<String> left = new ArrayList<>(this.leftLines);
        List<String> right = new ArrayList<>(this.rightLines);
        while (left.size() < 14) {
            left.add("");
        }
        while (right.size() < 14) {
            right.add("");
        }
        for (int i = 0; i < 14; i++) {
            ui.set("#L" + (i + 1) + ".Text", left.get(i));
            ui.set("#R" + (i + 1) + ".Text", right.get(i));
        }
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    public void setLeftLines(@Nonnull List<String> lines) {
        this.leftLines = List.copyOf(lines);
    }

    public void setRightLines(@Nonnull List<String> lines) {
        this.rightLines = List.copyOf(lines);
    }
}
