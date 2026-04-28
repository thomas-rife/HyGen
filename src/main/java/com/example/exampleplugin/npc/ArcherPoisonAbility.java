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
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ArcherPoisonAbility implements HeroAbility {
    public static final String ARCHER_ROLE_ID = "HyGen_Companion_Archer";
    public static final String EFFECT_ID = "HyGen_Archer_Ability_Active";
    public static final long COOLDOWN_MS = 6_000L;
    private static final int FIRST_PARTY_SLOT = 1;
    private static final int LAST_PARTY_SLOT = 3;

    private static final ConcurrentHashMap<AbilityKey, AbilityState> STATES = new ConcurrentHashMap<>();

    public ArcherPoisonAbility() {
    }

    @Nonnull
    @Override
    public String getRoleId() {
        return ARCHER_ROLE_ID;
    }

    @Override
    public boolean tryActivate(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> playerRef) {
        Integer activeSlot = TrackedSummonStore.getActivePartySlot(playerRef);
        if (activeSlot == null || !isArcherSlot(playerRef, activeSlot)) {
            return false;
        }

        AbilityKey key = keyForCurrentRun(activeSlot);
        if (key == null) {
            return false;
        }

        long now = System.currentTimeMillis();
        AbilityState state = STATES.computeIfAbsent(key, ignored -> new AbilityState());
        if (now < state.cooldownUntilMs || state.isLoaded) {
            return false;
        }

        state.isLoaded = true;
        // Cooldown starts after firing
        return true;
    }

    @Override
    public float getCooldownProgress(int slot) {
        AbilityKey key = keyForCurrentRun(slot);
        AbilityState state = key == null ? null : STATES.get(key);
        if (state == null) {
            return 1.0f;
        }
        
        if (state.isLoaded) {
            return 1.0f;
        }

        long now = System.currentTimeMillis();
        if (now >= state.cooldownUntilMs) {
            return 1.0f;
        }
        return clamp(1.0f - (float)(state.cooldownUntilMs - now) / (float)COOLDOWN_MS);
    }

    @Override
    public boolean isAbilityActive(int slot) {
        AbilityKey key = keyForCurrentRun(slot);
        AbilityState state = key == null ? null : STATES.get(key);
        return state != null && state.isLoaded;
    }

    @Override
    @Nullable
    public String getActivationEffectId() {
        return EFFECT_ID;
    }
    
    public static boolean isArcherSlot(@Nonnull Ref<EntityStore> partyRef, int slot) {
        Integer roleIndex = TrackedSummonStore.getPartyRoleIndex(partyRef, slot);
        if (roleIndex == null) {
            return false;
        }
        String roleName = NPCPlugin.get().getName(roleIndex);
        return ARCHER_ROLE_ID.equals(roleName);
    }
    
    public static boolean consumeLoadedArrow(int slot) {
        AbilityKey key = keyForCurrentRun(slot);
        AbilityState state = key == null ? null : STATES.get(key);
        if (state != null && state.isLoaded) {
            state.isLoaded = false;
            state.cooldownUntilMs = System.currentTimeMillis() + COOLDOWN_MS;
            return true;
        }
        return false;
    }
    
    public static boolean hasLoadedArrow(int slot) {
        AbilityKey key = keyForCurrentRun(slot);
        AbilityState state = key == null ? null : STATES.get(key);
        return state != null && state.isLoaded;
    }

    @Nullable
    public static Ref<EntityStore> getOwnerRefForCurrentRun(@Nonnull Store<EntityStore> store) {
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
        private volatile boolean isLoaded = false;
        private volatile long cooldownUntilMs;
    }
}
