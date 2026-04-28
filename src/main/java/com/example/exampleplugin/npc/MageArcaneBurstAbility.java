package com.example.exampleplugin.npc;

import com.example.exampleplugin.levels.LevelSessionManager;
import com.example.exampleplugin.levels.LevelRunCombatStore;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageSystems;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class MageArcaneBurstAbility implements HeroAbility {
    public static final String MAGE_ROLE_ID = "HyGen_Companion_Mage";
    public static final String ACTIVATION_EFFECT_ID = "HyGen_Mage_Ability_Active";
    public static final long ACTIVE_MS = 1_000L;
    public static final long COOLDOWN_MS = 10_000L;
    public static final float BURST_DAMAGE = 80.0f;
    public static final float BURST_RADIUS = 7.0f;

    private static final ConcurrentHashMap<AbilityKey, AbilityState> STATES = new ConcurrentHashMap<>();

    @Nonnull
    @Override
    public String getRoleId() {
        return MAGE_ROLE_ID;
    }

    @Override
    public boolean tryActivate(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> playerRef) {
        Integer activeSlot = TrackedSummonStore.getActivePartySlot(playerRef);
        if (activeSlot == null || !isMageSlot(playerRef, activeSlot)) {
            return false;
        }

        AbilityKey key = keyForCurrentRun(activeSlot);
        if (key == null) {
            return false;
        }

        long now = System.currentTimeMillis();
        AbilityState state = STATES.computeIfAbsent(key, ignored -> new AbilityState());
        if (now < state.cooldownUntilMs || now < state.activeUntilMs) {
            return false;
        }

        LevelRunCombatStore.CombatWorldState combatState = getCombatState(store);
        if (combatState == null) {
            return false;
        }

        Ref<EntityStore> casterRef = resolveCasterEntity(playerRef, activeSlot);
        if (!CombatTargetingUtil.isAlive(store, casterRef)) {
            return false;
        }

        applyBurst(store, casterRef, combatState);

        state.activeUntilMs = now + ACTIVE_MS;
        state.cooldownUntilMs = state.activeUntilMs + COOLDOWN_MS;
        return true;
    }

    @Override
    public float getCooldownProgress(int slot) {
        AbilityKey key = keyForCurrentRun(slot);
        AbilityState state = key == null ? null : STATES.get(key);
        if (state == null) {
            return 1.0f;
        }

        long now = System.currentTimeMillis();
        if (now < state.activeUntilMs) {
            return 1.0f;
        }
        if (now >= state.cooldownUntilMs) {
            return 1.0f;
        }
        return clamp((float) (now - state.activeUntilMs) / (float) COOLDOWN_MS);
    }

    @Override
    public boolean isAbilityActive(int slot) {
        AbilityKey key = keyForCurrentRun(slot);
        AbilityState state = key == null ? null : STATES.get(key);
        return state != null && System.currentTimeMillis() < state.activeUntilMs;
    }

    @Override
    @Nullable
    public String getActivationEffectId() {
        return ACTIVATION_EFFECT_ID;
    }

    public static boolean isMageSlot(@Nonnull Ref<EntityStore> partyRef, int slot) {
        Integer roleIndex = TrackedSummonStore.getPartyRoleIndex(partyRef, slot);
        if (roleIndex == null) {
            return false;
        }
        String roleName = NPCPlugin.get().getName(roleIndex);
        return MAGE_ROLE_ID.equals(roleName);
    }

    public static boolean isActiveForSlot(int slot) {
        AbilityKey key = keyForCurrentRun(slot);
        AbilityState state = key == null ? null : STATES.get(key);
        return state != null && System.currentTimeMillis() < state.activeUntilMs;
    }

    @Nullable
    private static LevelRunCombatStore.CombatWorldState getCombatState(@Nonnull Store<EntityStore> store) {
        LevelSessionManager.ActiveRunSnapshot snapshot = LevelSessionManager.get().getSnapshot();
        if (snapshot == null || snapshot.runWorldUuid() == null) {
            return null;
        }

        UUID worldId = store.getExternalData().getWorld().getWorldConfig().getUuid();
        if (!snapshot.runWorldUuid().equals(worldId)) {
            return null;
        }
        return LevelRunCombatStore.get().getWorld(worldId);
    }

    @Nullable
    private static void applyBurst(
        @Nonnull Store<EntityStore> store,
        @Nonnull Ref<EntityStore> casterRef,
        @Nonnull LevelRunCombatStore.CombatWorldState combatState
    ) {
        TransformComponent casterTransform = store.getComponent(casterRef, TransformComponent.getComponentType());
        if (casterTransform == null) {
            return;
        }

        for (Ref<EntityStore> enemyRef : combatState.activeEnemies()) {
            if (!CombatTargetingUtil.isLiveEnemyTarget(store, enemyRef)) {
                continue;
            }
            TransformComponent enemyTransform = store.getComponent(enemyRef, TransformComponent.getComponentType());
            if (enemyTransform == null) {
                continue;
            }
            if (casterTransform.getPosition().distanceTo(enemyTransform.getPosition()) > BURST_RADIUS) {
                continue;
            }
            
            Damage damage = new Damage(new Damage.EntitySource(casterRef), DamageCause.ENVIRONMENT, BURST_DAMAGE);
            DamageSystems.executeDamage(enemyRef, store, damage);
            CombatTargetingUtil.setTargetHostile(store, enemyRef, casterRef);
        }
    }

    @Nonnull
    private static Ref<EntityStore> resolveCasterEntity(@Nonnull Ref<EntityStore> playerRef, int slot) {
        Ref<EntityStore> slotPlayerRef = TrackedSummonStore.getPlayerForPartySlot(playerRef, slot);
        if (slotPlayerRef != null) {
            return slotPlayerRef;
        }
        Ref<EntityStore> slotNpcRef = TrackedSummonStore.getNpcForUtilitySlot(playerRef, slot);
        if (slotNpcRef != null) {
            return slotNpcRef;
        }
        return playerRef;
    }

    @Nullable
    private static AbilityKey keyForCurrentRun(int slot) {
        LevelSessionManager.ActiveRunSnapshot snapshot = LevelSessionManager.get().getSnapshot();
        if (snapshot == null || snapshot.runWorldUuid() == null) {
            return null;
        }
        return new AbilityKey(snapshot.runWorldUuid(), slot);
    }

    private static float clamp(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }

    private record AbilityKey(@Nonnull UUID runWorldUuid, int slot) {
    }

    private static final class AbilityState {
        private volatile long activeUntilMs;
        private volatile long cooldownUntilMs;
    }
}
