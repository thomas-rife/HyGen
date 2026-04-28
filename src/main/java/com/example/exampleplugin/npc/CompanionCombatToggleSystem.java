package com.example.exampleplugin.npc;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.server.core.entity.Frozen;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.List;

public class CompanionCombatToggleSystem extends TickingSystem<EntityStore> {
    private long lastTickMs;
    
    // Set this to true to temporarily disable the freezing of NPCs without targets
    private static final boolean DISABLE_FREEZE = true;

    @Override
    public void tick(float dt, int systemIndex, @Nonnull Store<EntityStore> store) {
        if (DISABLE_FREEZE) {
            unfreezeAllTracked(store);
            return;
        }
        long now = System.currentTimeMillis();
        if (now - this.lastTickMs < 350L) {
            return;
        }
        this.lastTickMs = now;

        for (PlayerRef playerRef : store.getExternalData().getWorld().getPlayerRefs()) {
            Ref<EntityStore> playerEntityRef = playerRef.getReference();
            if (playerEntityRef == null || !playerEntityRef.isValid()) {
                continue;
            }
            List<Ref<EntityStore>> tracked = TrackedSummonStore.getTrackedSnapshot(playerEntityRef);
            for (Ref<EntityStore> npcRef : tracked) {
                if (npcRef == null || !npcRef.isValid() || npcRef.getStore() != store) {
                    continue;
                }
                boolean hasStoredAssignment = TrackedSummonStore.syncStoredTargetForNpc(playerEntityRef, npcRef, store);
                Ref<EntityStore> targetRef = CombatTargetingUtil.getCurrentLockedTarget(store, npcRef);
                
                boolean isHealer = CombatTargetingUtil.classifyRole(store, npcRef, false) == CombatTargetingUtil.TacticalRole.ALLY_SUPPORT;
                boolean hasValidTarget = isHealer 
                    ? CombatTargetingUtil.isAlive(store, targetRef) && CombatTargetingUtil.areAlliedInRun(store, playerEntityRef, targetRef)
                    : CombatTargetingUtil.isLiveEnemyTarget(store, targetRef);

                if (!hasValidTarget && targetRef != null) {
                    CombatTargetingUtil.clearMarkedTarget(store, npcRef);
                    TrackedSummonStore.clearTargetForNpc(playerEntityRef, npcRef, targetRef);
                }

                if (hasValidTarget || hasStoredAssignment) {
                    if (store.getComponent(npcRef, Frozen.getComponentType()) != null) {
                        store.removeComponent(npcRef, Frozen.getComponentType());
                    }
                } else {
                    if (store.getComponent(npcRef, Frozen.getComponentType()) == null) {
                        store.putComponent(npcRef, Frozen.getComponentType(), Frozen.get());
                    }
                }
            }
        }
    }

    private void unfreezeAllTracked(@Nonnull Store<EntityStore> store) {
        for (PlayerRef playerRef : store.getExternalData().getWorld().getPlayerRefs()) {
            Ref<EntityStore> playerEntityRef = playerRef.getReference();
            if (playerEntityRef == null || !playerEntityRef.isValid()) {
                continue;
            }
            List<Ref<EntityStore>> tracked = TrackedSummonStore.getTrackedSnapshot(playerEntityRef);
            for (Ref<EntityStore> npcRef : tracked) {
                if (npcRef == null || !npcRef.isValid() || npcRef.getStore() != store) {
                    continue;
                }
                if (store.getComponent(npcRef, Frozen.getComponentType()) != null) {
                    store.removeComponent(npcRef, Frozen.getComponentType());
                }
            }
        }
    }
}
