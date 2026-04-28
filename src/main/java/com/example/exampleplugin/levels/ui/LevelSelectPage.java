package com.example.exampleplugin.levels.ui;

import com.example.exampleplugin.levels.LevelConfigStore;
import com.example.exampleplugin.levels.LevelProgressStore;
import com.example.exampleplugin.levels.LevelSessionManager;
import com.example.exampleplugin.levels.model.LevelDefinition;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public class LevelSelectPage extends InteractiveCustomUIPage<LevelSelectPage.Data> {
    public static class Data {
        public String action;
        public static final BuilderCodec<Data> CODEC = BuilderCodec.builder(Data.class, Data::new)
            .addField(new KeyedCodec<>("Action", Codec.STRING), (d, v) -> d.action = v, d -> d.action)
            .build();
    }

    public LevelSelectPage(@Nonnull PlayerRef playerRef) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, Data.CODEC);
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder ui, @Nonnull UIEventBuilder events, @Nonnull Store<EntityStore> store) {
        ui.append("Pages/LevelSelectPage.ui");
        
        List<LevelDefinition> levels = LevelConfigStore.get().getLevels().stream()
            .sorted(Comparator.comparingInt(LevelSelectPage::sortOrder))
            .toList();

        UUID playerUuid = this.playerRef.getUuid();
        boolean foundNext = false;

        for (int i = 0; i < levels.size() && i < 6; i++) {
            LevelDefinition level = levels.get(i);
            String levelId = safe(level.levelId, "level_" + (i + 1));
            String baseSelector = String.format("#LevelNode%02d", i + 1);

            boolean completed = LevelProgressStore.get().isLevelCompleted(playerUuid, levelId);
            String suffix;
            String btnSuffix;
            if (completed) {
                suffix = "C";
                btnSuffix = "BtnC";
            } else if (!foundNext) {
                suffix = "U";
                btnSuffix = "BtnU";
                foundNext = true;
            } else {
                suffix = "L";
                btnSuffix = "BtnL";
            }

            ui.set(baseSelector + "C.Visible", suffix.equals("C"));
            ui.set(baseSelector + "U.Visible", suffix.equals("U"));
            ui.set(baseSelector + "L.Visible", suffix.equals("L"));

            events.addEventBinding(
                CustomUIEventBindingType.Activating,
                baseSelector + btnSuffix,
                EventData.of("Action", "start:" + levelId),
                false
            );
        }

        events.addEventBinding(CustomUIEventBindingType.Activating, "#BackBtn", EventData.of("Action", "back"), false);
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull Data data) {
        String action = data.action == null ? "" : data.action.trim().toLowerCase();
        if (action.startsWith("start:")) {
            String levelId = data.action.substring("start:".length()).trim();
            LevelSessionManager.get().startLevelForPlayer(this.playerRef, levelId).whenComplete((result, throwable) -> {
                if (throwable != null) {
                    String reason = throwable.getCause() != null ? throwable.getCause().getMessage() : throwable.getMessage();
                    this.playerRef.sendMessage(Message.raw("Failed to start level: " + reason));
                    return;
                }
                this.playerRef.sendMessage(Message.raw("Started " + result.levelName() + "."));
            });
            close();
            return;
        }
        if ("back".equals(action)) {
            Player player = store.getComponent(ref, Player.getComponentType());
            if (player != null) {
                player.getPageManager().openCustomPage(ref, store, new MainMenuPage(this.playerRef));
            }
        }
    }

    @Nonnull
    private static String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static int sortOrder(@Nonnull LevelDefinition level) {
        return level.orderIndex == null ? Integer.MAX_VALUE : level.orderIndex;
    }
}
