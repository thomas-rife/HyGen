package com.example.exampleplugin.levels.ui;

import com.example.exampleplugin.npc.BattleheartCameraPreferences;
import com.example.exampleplugin.npc.BattleheartCameraService;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.UUID;

public class MainMenuPage extends InteractiveCustomUIPage<MainMenuPage.Data> {
    public static class Data {
        public String action;
        public static final BuilderCodec<Data> CODEC = BuilderCodec.builder(Data.class, Data::new)
            .addField(new KeyedCodec<>("Action", Codec.STRING), (d, v) -> d.action = v, d -> d.action)
            .build();
    }

    public MainMenuPage(@Nonnull PlayerRef playerRef) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, Data.CODEC);
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder ui, @Nonnull UIEventBuilder events, @Nonnull Store<EntityStore> store) {
        ui.append("Pages/MainMenuPage.ui");

        boolean thirdPerson = BattleheartCameraPreferences.isThirdPersonCameraEnabled(this.playerRef.getUuid());
        ui.set("#CameraTopBtn.Visible", !thirdPerson);
        ui.set("#CameraBackBtn.Visible", thirdPerson);

        events.addEventBinding(CustomUIEventBindingType.Activating, "#LevelsBtn", EventData.of("Action", "levels"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#HeroesBtn", EventData.of("Action", "heroes"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#CameraTopBtn", EventData.of("Action", "camera"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#CameraBackBtn", EventData.of("Action", "camera"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#AiWorldGenBtn", EventData.of("Action", "aiworldgen"), false);
        
        BattleheartCameraService.applyMenuCamera(this.playerRef);
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull Data data) {
        String action = data.action == null ? "" : data.action.trim().toLowerCase();
        if ("levels".equals(action)) {
            Player player = store.getComponent(ref, Player.getComponentType());
            if (player != null) {
                player.getPageManager().openCustomPage(ref, store, new LevelSelectPage(this.playerRef));
            }
        } else if ("heroes".equals(action)) {
            Player player = store.getComponent(ref, Player.getComponentType());
            if (player != null) {
                player.getPageManager().openCustomPage(ref, store, new HeroSelectPage(this.playerRef));
            }
        } else if ("aiworldgen".equals(action)) {
            Player player = store.getComponent(ref, Player.getComponentType());
            if (player != null) {
                player.getPageManager().openCustomPage(ref, store, new AiWorldGenPage(this.playerRef));
            }
        } else if ("camera".equals(action)) {
            // Toggle the preference for IN-GAME only
            BattleheartCameraPreferences.toggleThirdPersonCamera(this.playerRef.getUuid());

            // Re-open the page to update the label without changing the camera view
            Player player = store.getComponent(ref, Player.getComponentType());
            if (player != null) {
                player.getPageManager().openCustomPage(ref, store, new MainMenuPage(this.playerRef));
            }
        }
    }

    @Nonnull
    private static String cameraButtonText(@Nonnull UUID playerUuid) {
        return BattleheartCameraPreferences.isThirdPersonCameraEnabled(playerUuid)
            ? "Camera: Third Person"
            : "Camera: Overhead";
    }
}
