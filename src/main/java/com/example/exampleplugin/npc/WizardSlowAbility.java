package com.example.exampleplugin.npc;

import com.example.exampleplugin.levels.LevelRunCombatStore;
import com.example.exampleplugin.levels.LevelSessionManager;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class WizardSlowAbility implements HeroAbility {
    public static final String WIZARD_ROLE_ID = "HyGen_Companion_Wizard";
    public static final String EFFECT_ID = "HyGen_Wizard_Slow";
    public static final String ACTIVATION_EFFECT_ID = "HyGen_Wizard_Ability_Active";
    public static final long ACTIVE_MS = 3_000L;
    public static final long COOLDOWN_MS = 10_000L;

    private static final ConcurrentHashMap<AbilityKey, AbilityState> STATES = new ConcurrentHashMap<>();

    @Nonnull
    @Override
    public String getRoleId() {
        return WIZARD_ROLE_ID;
    }

    @Override
    public boolean tryActivate(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> playerRef) {
        Integer activeSlot = TrackedSummonStore.getActivePartySlot(playerRef);
        if (activeSlot == null || !isWizardSlot(playerRef, activeSlot)) {
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

        EntityEffect slowEffect = EntityEffect.getAssetMap().getAsset(EFFECT_ID);
        LevelRunCombatStore.CombatWorldState combatState = getCombatState(store);
        if (slowEffect == null || combatState == null) {
            return false;
        }

        boolean appliedToAnyEnemy = false;
        for (Ref<EntityStore> enemyRef : combatState.activeEnemies()) {
            if (!CombatTargetingUtil.isLiveEnemyTarget(store, enemyRef)) {
                continue;
            }

            EffectControllerComponent effects = store.getComponent(enemyRef, EffectControllerComponent.getComponentType());
            if (effects == null) {
                effects = new EffectControllerComponent();
            }

            if (effects.addEffect(enemyRef, slowEffect, store)) {
                store.putComponent(enemyRef, EffectControllerComponent.getComponentType(), effects);
                appliedToAnyEnemy = true;
            }
        }

        if (!appliedToAnyEnemy) {
            return false;
        }

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

    public static boolean isWizardSlot(@Nonnull Ref<EntityStore> partyRef, int slot) {
        Integer roleIndex = TrackedSummonStore.getPartyRoleIndex(partyRef, slot);
        if (roleIndex == null) {
            return false;
        }
        String roleName = NPCPlugin.get().getName(roleIndex);
        return WIZARD_ROLE_ID.equals(roleName);
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
