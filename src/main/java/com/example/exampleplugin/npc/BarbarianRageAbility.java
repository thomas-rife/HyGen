package com.example.exampleplugin.npc;

import com.example.exampleplugin.levels.LevelSessionManager;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class BarbarianRageAbility implements HeroAbility {
    public static final String BARBARIAN_ROLE_ID = "HyGen_Companion_Barbarian";
    public static final String EFFECT_ID = "HyGen_Barbarian_Rage";
    public static final long ACTIVE_MS = 5_000L;
    public static final long COOLDOWN_MS = 10_000L;
    public static final float DAMAGE_MULTIPLIER = 1.50f;

    private static final ConcurrentHashMap<AbilityKey, AbilityState> STATES = new ConcurrentHashMap<>();

    @Nonnull
    @Override
    public String getRoleId() {
        return BARBARIAN_ROLE_ID;
    }

    @Override
    public boolean tryActivate(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> playerRef) {
        Integer activeSlot = TrackedSummonStore.getActivePartySlot(playerRef);
        if (activeSlot == null || !isBarbarianSlot(playerRef, activeSlot)) {
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
        return EFFECT_ID;
    }

    public static boolean isBarbarianSlot(@Nonnull Ref<EntityStore> partyRef, int slot) {
        Integer roleIndex = TrackedSummonStore.getPartyRoleIndex(partyRef, slot);
        if (roleIndex == null) {
            return false;
        }
        String roleName = NPCPlugin.get().getName(roleIndex);
        return BARBARIAN_ROLE_ID.equals(roleName);
    }

    public static boolean isActiveForSlot(int slot) {
        AbilityKey key = keyForCurrentRun(slot);
        AbilityState state = key == null ? null : STATES.get(key);
        return state != null && System.currentTimeMillis() < state.activeUntilMs;
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
