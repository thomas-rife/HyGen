package com.example.exampleplugin.levels;

import com.example.exampleplugin.hud.HudUtils;
import com.example.exampleplugin.hud.LevelEditorHud;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class LevelEditorHudSystem extends TickingSystem<EntityStore> {
    private final ConcurrentHashMap<UUID, LevelEditorHud> huds = new ConcurrentHashMap<>();
    private long lastUpdateMs;

    @Override
    public void tick(float dt, int systemIndex, @Nonnull Store<EntityStore> store) {
        long now = System.currentTimeMillis();
        if (now - this.lastUpdateMs < 700L) {
            return;
        }
        this.lastUpdateMs = now;

        World world = store.getExternalData().getWorld();
        for (PlayerRef playerRef : world.getPlayerRefs()) {
            boolean enabled = LevelEditorManager.get().isHudEnabled(playerRef.getUuid());
            if (!enabled) {
                hideHud(playerRef);
                continue;
            }
            showHud(playerRef);
        }
    }

    private void showHud(@Nonnull PlayerRef playerRef) {
        Ref<EntityStore> ref = playerRef.getReference();
        if (ref == null || !ref.isValid()) {
            return;
        }
        Store<EntityStore> entityStore = ref.getStore();
        entityStore.getExternalData().getWorld().execute(() -> {
            if (!ref.isValid()) {
                return;
            }
            Player player = entityStore.getComponent(ref, Player.getComponentType());
            if (player == null) {
                return;
            }
            LevelEditorHud hud = this.huds.computeIfAbsent(playerRef.getUuid(), ignored -> new LevelEditorHud(playerRef));
            hud.setLeftLines(LevelEditorManager.get().buildLeftHudLines(playerRef));
            hud.setRightLines(LevelEditorManager.get().buildRightHudLines(playerRef));
            hud.setVisible(true);
            if (player.getHudManager().getCustomHud() != hud) {
                player.getHudManager().setCustomHud(playerRef, hud);
            } else {
                hud.refresh();
            }
        });
    }

    private void hideHud(@Nonnull PlayerRef playerRef) {
        LevelEditorHud hud = this.huds.get(playerRef.getUuid());
        if (hud == null) {
            return;
        }
        Ref<EntityStore> ref = playerRef.getReference();
        if (ref == null || !ref.isValid()) {
            return;
        }
        Store<EntityStore> entityStore = ref.getStore();
        entityStore.getExternalData().getWorld().execute(() -> {
            if (!ref.isValid()) {
                return;
            }
            Player player = entityStore.getComponent(ref, Player.getComponentType());
            if (player == null) {
                return;
            }
            if (player.getHudManager().getCustomHud() instanceof LevelEditorHud) {
                HudUtils.clearHudSafely(player, playerRef);
            }
        });
    }
}
