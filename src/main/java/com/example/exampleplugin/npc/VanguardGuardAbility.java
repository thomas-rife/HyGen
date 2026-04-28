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

public final class VanguardGuardAbility implements HeroAbility {
    public static final String VANGUARD_ROLE_ID = "HyGen_Companion_Vanguard";
    public static final String EFFECT_ID = "HyGen_Vanguard_Guard";
    public static final long ACTIVE_MS = 10_000L;
    public static final long COOLDOWN_MS = 10_000L;
    public static final float DAMAGE_MULTIPLIER = 0.20f;
    private static final int FIRST_PARTY_SLOT = 1;
    private static final int LAST_PARTY_SLOT = 3;

    private static final ConcurrentHashMap<GuardKey, GuardState> STATES = new ConcurrentHashMap<>();

    public VanguardGuardAbility() {
    }

    @Nonnull
    @Override
    public String getRoleId() {
        return VANGUARD_ROLE_ID;
    }

    @Override
    public boolean tryActivate(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> playerRef) {
        return activate(store, playerRef);
    }

    @Override
    public float getCooldownProgress(int slot) {
        return cooldownProgress(slot);
    }

    @Override
    public boolean isAbilityActive(int slot) {
        return isActiveForSlot(slot);
    }

    @Override
    @Nullable
    public String getActivationEffectId() {
        return EFFECT_ID;
    }

    @Override
    public void tick(@Nonnull Store<EntityStore> store, long now) {
        cleanupExpired();
    }

    private static boolean activate(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> playerRef) {
        Integer activeSlot = TrackedSummonStore.getActivePartySlot(playerRef);
        if (activeSlot == null || !isVanguardSlot(playerRef, activeSlot)) {
            return false;
        }

        GuardKey key = keyForCurrentRun(activeSlot);
        if (key == null) {
            return false;
        }

        long now = System.currentTimeMillis();
        GuardState state = STATES.computeIfAbsent(key, ignored -> new GuardState());
        if (now < state.cooldownUntilMs) {
            return false;
        }

        state.activeUntilMs = now + ACTIVE_MS;
        state.cooldownUntilMs = state.activeUntilMs + COOLDOWN_MS;
        return true;
    }

    public static boolean isActiveForTarget(@Nonnull Store<EntityStore> store, @Nullable Ref<EntityStore> targetRef) {
        if (!CombatTargetingUtil.isAlive(store, targetRef)) {
            return false;
        }

        LevelSessionManager.ActiveRunSnapshot snapshot = LevelSessionManager.get().getSnapshot();
        Ref<EntityStore> ownerRef = getOwnerRef(snapshot, store);
        if (snapshot == null || ownerRef == null) {
            return false;
        }

        long now = System.currentTimeMillis();
        for (int slot = FIRST_PARTY_SLOT; slot <= LAST_PARTY_SLOT; slot++) {
            GuardKey key = new GuardKey(snapshot.runWorldUuid(), slot);
            GuardState state = STATES.get(key);
            if (state == null || now >= state.activeUntilMs) {
                continue;
            }
            Ref<EntityStore> slotEntity = getPartySlotEntity(ownerRef, slot);
            if (targetRef.equals(slotEntity)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isActiveForSlot(int slot) {
        GuardKey key = keyForCurrentRun(slot);
        GuardState state = key == null ? null : STATES.get(key);
        return state != null && System.currentTimeMillis() < state.activeUntilMs;
    }

    public static float cooldownProgress(int slot) {
        GuardKey key = keyForCurrentRun(slot);
        GuardState state = key == null ? null : STATES.get(key);
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
        return clamp((float)(now - state.activeUntilMs) / (float)COOLDOWN_MS);
    }

    public static boolean isVanguardSlot(@Nonnull Ref<EntityStore> partyRef, int slot) {
        Integer roleIndex = TrackedSummonStore.getPartyRoleIndex(partyRef, slot);
        if (roleIndex == null) {
            return false;
        }
        String roleName = NPCPlugin.get().getName(roleIndex);
        return VANGUARD_ROLE_ID.equals(roleName);
    }

    @Nullable
    public static Ref<EntityStore> getPartySlotEntity(@Nonnull Ref<EntityStore> partyRef, int slot) {
        Ref<EntityStore> player = TrackedSummonStore.getPlayerForPartySlot(partyRef, slot);
        return player != null ? player : TrackedSummonStore.getNpcForUtilitySlot(partyRef, slot);
    }

    public static void cleanupExpired() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<GuardKey, GuardState>> iterator = STATES.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<GuardKey, GuardState> entry = iterator.next();
            if (now >= entry.getValue().activeUntilMs && now >= entry.getValue().cooldownUntilMs) {
                iterator.remove();
            }
        }
    }

    @Nullable
    public static Ref<EntityStore> getOwnerRefForCurrentRun(@Nonnull Store<EntityStore> store) {
        return getOwnerRef(LevelSessionManager.get().getSnapshot(), store);
    }

    @Nullable
    private static GuardKey keyForCurrentRun(int slot) {
        LevelSessionManager.ActiveRunSnapshot snapshot = LevelSessionManager.get().getSnapshot();
        if (snapshot == null || snapshot.runWorldUuid() == null) {
            return null;
        }
        return new GuardKey(snapshot.runWorldUuid(), slot);
    }

    @Nullable
    private static Ref<EntityStore> getOwnerRef(@Nullable LevelSessionManager.ActiveRunSnapshot snapshot, @Nonnull Store<EntityStore> store) {
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

    private static float clamp(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }

    private record GuardKey(@Nonnull UUID runWorldUuid, int slot) {
    }

    private static final class GuardState {
        private volatile long activeUntilMs;
        private volatile long cooldownUntilMs;
    }
}
