package com.example.exampleplugin.npc;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.List;

public class CompanionAttitudeRefreshSystem extends TickingSystem<EntityStore> {
    private long lastTickMs;

    @Override
    public void tick(float dt, int systemIndex, @Nonnull Store<EntityStore> store) {
        long now = System.currentTimeMillis();
        if (now - this.lastTickMs < 1000L) {
            return;
        }
        this.lastTickMs = now;

        World world = store.getExternalData().getWorld();
        for (PlayerRef playerRef : world.getPlayerRefs()) {
            Ref<EntityStore> playerEntityRef = playerRef.getReference();
            if (playerEntityRef == null || !playerEntityRef.isValid()) {
                continue;
            }
            List<Ref<EntityStore>> companions = TrackedSummonStore.getTrackedSnapshot(playerEntityRef);
            for (Ref<EntityStore> npcRef : companions) {
                if (npcRef == null || !npcRef.isValid() || npcRef.getStore() != store) {
                    continue;
                }
                NpcAttitudeService.configureCompanionAttitudes(store, world, playerEntityRef, npcRef);
            }
        }
    }
}
