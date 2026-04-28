package com.example.exampleplugin.npc;

import com.example.exampleplugin.levels.LevelSessionManager;
import com.example.exampleplugin.levels.RunPartyHudSystem;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class HealerLogicSystem extends TickingSystem<EntityStore> {
    private static final float HEAL_PERCENT_PER_SECOND = 0.01f;
    private static final double HEAL_RANGE = 20.0;

    @Override
    public void tick(float dt, int systemIndex, @Nonnull Store<EntityStore> store) {
        LevelSessionManager.ActiveRunSnapshot snapshot = LevelSessionManager.get().getSnapshot();
        if (snapshot == null || snapshot.runWorldUuid() == null) {
            return;
        }

        World world = store.getExternalData().getWorld();
        if (!snapshot.runWorldUuid().equals(world.getWorldConfig().getUuid())) {
            return;
        }

        PlayerRef ownerPlayerRef = Universe.get().getPlayer(snapshot.ownerPlayerUuid());
        if (ownerPlayerRef == null || ownerPlayerRef.getReference() == null) {
            return;
        }

        Ref<EntityStore> ownerRef = ownerPlayerRef.getReference();
        if (ownerRef == null || !ownerRef.isValid() || ownerRef.getStore() != store) {
            return;
        }
        float deltaSeconds = Math.max(0f, dt);
        if (deltaSeconds <= 0f) {
            return;
        }

        Integer activePartySlot = TrackedSummonStore.getActivePartySlot(ownerRef);
        if (activePartySlot == null || activePartySlot < 1 || activePartySlot > 3) {
            return;
        }

        Integer roleIndex = TrackedSummonStore.getPartyRoleIndex(ownerRef, activePartySlot);
        if (roleIndex == null) {
            return;
        }
        String roleName = com.hypixel.hytale.server.npc.NPCPlugin.get().getName(roleIndex);
        if (!isHealingRole(roleName)) {
            return;
        }

        Ref<EntityStore> healerRef = resolveHealerEntity(ownerRef, activePartySlot);
        if (!CombatTargetingUtil.isAlive(store, healerRef)) {
            return;
        }

        Ref<EntityStore> targetRef = TrackedSummonStore.getTargetForPartySlot(ownerRef, activePartySlot, store);
        if (targetRef == null || !CombatTargetingUtil.areAlliedInRun(store, healerRef, targetRef)) {
            return;
        }

        if (!isInRange(store, healerRef, targetRef)) {
            return;
        }

        healTarget(store, targetRef, deltaSeconds);
    }

    @Nullable
    private static Ref<EntityStore> resolveHealerEntity(@Nonnull Ref<EntityStore> ownerRef, int partySlot) {
        Ref<EntityStore> playerRef = TrackedSummonStore.getPlayerForPartySlot(ownerRef, partySlot);
        if (playerRef != null) {
            return playerRef;
        }
        return TrackedSummonStore.getNpcForUtilitySlot(ownerRef, partySlot);
    }

    private static boolean isInRange(
        @Nonnull Store<EntityStore> store,
        @Nonnull Ref<EntityStore> healerRef,
        @Nonnull Ref<EntityStore> targetRef
    ) {
        TransformComponent healerTransform = store.getComponent(healerRef, TransformComponent.getComponentType());
        TransformComponent targetTransform = store.getComponent(targetRef, TransformComponent.getComponentType());
        if (healerTransform == null || targetTransform == null) {
            return false;
        }

        double distance = healerTransform.getPosition().distanceTo(targetTransform.getPosition());
        return distance <= HEAL_RANGE;
    }

    private static boolean isHealingRole(@Nullable String roleName) {
        if (roleName == null || roleName.isBlank()) {
            return false;
        }
        return roleName.contains("Companion_Support")
            || roleName.contains("Companion_Monk");
    }

    private static boolean healTarget(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> targetRef, float deltaSeconds) {
        EntityStatMap stats = store.getComponent(targetRef, EntityStatMap.getComponentType());
        if (stats == null) {
            return false;
        }

        int healthIndex = DefaultEntityStatTypes.getHealth();
        if (healthIndex < 0) {
            return false;
        }

        EntityStatValue health = stats.get(healthIndex);
        if (health == null) {
            return false;
        }

        float maxHealth = health.getMax();
        float currentHealth = health.get();
        if (maxHealth <= 0f || currentHealth >= maxHealth) {
            return false;
        }
        float healAmount = maxHealth * HEAL_PERCENT_PER_SECOND * deltaSeconds;
        float newHealth = Math.min(maxHealth, currentHealth + healAmount);
        if (newHealth <= currentHealth) {
            return false;
        }

        stats.setStatValue(healthIndex, newHealth);
        return true;
    }
}
