package com.example.exampleplugin.npc;

import com.example.exampleplugin.levels.LevelSessionManager;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class WardenHealingAuraAbility implements HeroAbility {
    public static final String WARDEN_ROLE_ID = "HyGen_Companion_Warden";
    public static final long COOLDOWN_MS = 15_000L;
    public static final long ACTIVE_MS = 5_000L;
    public static final float BURST_HEAL_PERCENT = 0.40f;
    public static final String EFFECT_ID = "HyGen_Warden_Healing_Aura";

    private static final ConcurrentHashMap<AbilityKey, AbilityState> STATES = new ConcurrentHashMap<>();

    @Nonnull
    @Override
    public String getRoleId() {
        return WARDEN_ROLE_ID;
    }

    @Override
    public boolean tryActivate(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> playerRef) {
        Integer activeSlot = TrackedSummonStore.getActivePartySlot(playerRef);
        if (activeSlot == null || !isWardenSlot(playerRef, activeSlot)) {
            return false;
        }

        AbilityKey key = keyForCurrentRun(activeSlot);
        if (key == null) {
            return false;
        }

        long now = System.currentTimeMillis();
        AbilityState state = STATES.computeIfAbsent(key, ignored -> new AbilityState());
        if (now < state.cooldownUntilMs) {
            return false;
        }

        Ref<EntityStore> ownerRef = getOwnerRefForCurrentRun(store);
        if (ownerRef == null) {
            return false;
        }

        boolean anyoneHealed = false;
        for (Ref<EntityStore> allyRef : collectAlliedTargets(ownerRef)) {
            if (!CombatTargetingUtil.isAlive(store, allyRef)) {
                continue;
            }

            float currentPercent = EntityHealthUtil.readHealthPercent(store, allyRef);
            if (currentPercent < 1.0f) {
                EntityHealthUtil.setHealthPercentOfMax(store, allyRef, currentPercent + BURST_HEAL_PERCENT);
                anyoneHealed = true;
            }
        }

        if (!anyoneHealed) {
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

    public static boolean isWardenSlot(@Nonnull Ref<EntityStore> partyRef, int slot) {
        Integer roleIndex = TrackedSummonStore.getPartyRoleIndex(partyRef, slot);
        if (roleIndex == null) {
            return false;
        }
        String roleName = NPCPlugin.get().getName(roleIndex);
        return WARDEN_ROLE_ID.equals(roleName);
    }

    @Nullable
    private static AbilityKey keyForCurrentRun(int slot) {
        LevelSessionManager.ActiveRunSnapshot snapshot = LevelSessionManager.get().getSnapshot();
        if (snapshot == null || snapshot.runWorldUuid() == null) {
            return null;
        }
        return new AbilityKey(snapshot.runWorldUuid(), slot);
    }

    @Nullable
    private static Ref<EntityStore> getOwnerRefForCurrentRun(@Nonnull Store<EntityStore> store) {
        LevelSessionManager.ActiveRunSnapshot snapshot = LevelSessionManager.get().getSnapshot();
        if (snapshot == null || snapshot.runWorldUuid() == null) {
            return null;
        }
        UUID worldId = store.getExternalData().getWorld().getWorldConfig().getUuid();
        if (!snapshot.runWorldUuid().equals(worldId)) {
            return null;
        }

        PlayerRef owner = Universe.get().getPlayer(snapshot.ownerPlayerUuid());
        if (owner == null) {
            return null;
        }
        Ref<EntityStore> ownerRef = owner.getReference();
        return ownerRef != null && ownerRef.isValid() && ownerRef.getStore() == store ? ownerRef : null;
    }

    @Nonnull
    private static Set<Ref<EntityStore>> collectAlliedTargets(@Nonnull Ref<EntityStore> ownerRef) {
        Set<Ref<EntityStore>> allies = new HashSet<>();
        allies.add(ownerRef);
        allies.addAll(TrackedSummonStore.getTrackedSnapshot(ownerRef));
        for (int slot = 1; slot <= 3; slot++) {
            Ref<EntityStore> playerRef = TrackedSummonStore.getPlayerForPartySlot(ownerRef, slot);
            if (playerRef != null) {
                allies.add(playerRef);
            }
            Ref<EntityStore> npcRef = TrackedSummonStore.getNpcForUtilitySlot(ownerRef, slot);
            if (npcRef != null) {
                allies.add(npcRef);
            }
        }
        allies.remove(null);
        return allies;
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
