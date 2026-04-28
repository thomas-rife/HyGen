package com.example.exampleplugin.npc;

import com.example.exampleplugin.levels.LevelRunCombatStore;
import com.example.exampleplugin.levels.LevelSessionManager;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.asset.type.attitude.Attitude;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.MarkedEntitySupport;
import com.hypixel.hytale.server.npc.role.support.WorldSupport;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

public final class CombatTargetingUtil {
    @Nullable
    private static final Field ATTITUDE_OVERRIDE_MEMORY_FIELD = resolveOverrideMemoryField();

    private CombatTargetingUtil() {
    }

    public static boolean isAlive(@Nonnull Store<EntityStore> store, @Nullable Ref<EntityStore> ref) {
        if (ref == null || !ref.isValid() || ref.getStore() != store) {
            return false;
        }
        return store.getComponent(ref, DeathComponent.getComponentType()) == null;
    }

    public static boolean isLiveEnemyTarget(@Nonnull Store<EntityStore> store, @Nullable Ref<EntityStore> ref) {
        if (!isAlive(store, ref)) {
            return false;
        }
        float health = readCurrentHealth(store, ref);
        if (health != Float.MAX_VALUE && health <= 0f) {
            return false;
        }
        return classifyTeam(store, ref) == CombatTeam.ENEMY_SIDE;
    }

    @Nullable
    public static Ref<EntityStore> chooseLowestHealthTarget(
        @Nonnull Store<EntityStore> store,
        @Nonnull List<Ref<EntityStore>> candidates
    ) {
        Ref<EntityStore> chosen = null;
        float chosenHealth = Float.MAX_VALUE;
        for (Ref<EntityStore> candidate : candidates) {
            if (!isAlive(store, candidate)) {
                continue;
            }
            float hp = readCurrentHealth(store, candidate);
            if (hp < chosenHealth) {
                chosenHealth = hp;
                chosen = candidate;
            }
        }
        if (chosen != null) {
            return chosen;
        }
        return chooseRandomAlive(store, candidates);
    }

    @Nullable
    public static Ref<EntityStore> chooseRandomAlive(
        @Nonnull Store<EntityStore> store,
        @Nonnull List<Ref<EntityStore>> candidates
    ) {
        int aliveCount = 0;
        for (Ref<EntityStore> candidate : candidates) {
            if (isAlive(store, candidate)) {
                aliveCount++;
            }
        }
        if (aliveCount == 0) {
            return null;
        }
        int pick = ThreadLocalRandom.current().nextInt(aliveCount);
        for (Ref<EntityStore> candidate : candidates) {
            if (!isAlive(store, candidate)) {
                continue;
            }
            if (pick == 0) {
                return candidate;
            }
            pick--;
        }
        return null;
    }

    public static float readCurrentHealth(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        EntityStatMap stats = store.getComponent(ref, EntityStatMap.getComponentType());
        if (stats == null) {
            return Float.MAX_VALUE;
        }
        int healthIndex = DefaultEntityStatTypes.getHealth();
        if (healthIndex < 0 || stats.get(healthIndex) == null) {
            return Float.MAX_VALUE;
        }
        return stats.get(healthIndex).get();
    }

    public static float readHealthPercent(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        EntityStatMap stats = store.getComponent(ref, EntityStatMap.getComponentType());
        if (stats == null) {
            return 1.0f;
        }
        int healthIndex = DefaultEntityStatTypes.getHealth();
        if (healthIndex < 0 || stats.get(healthIndex) == null) {
            return 1.0f;
        }
        float max = stats.get(healthIndex).getMax();
        if (max <= 0f) {
            return 1.0f;
        }
        return Math.max(0f, Math.min(1f, stats.get(healthIndex).get() / max));
    }

    @Nonnull
    public static TacticalRole classifyRole(
        @Nonnull Store<EntityStore> store,
        @Nullable Ref<EntityStore> ref,
        boolean treatPlayerAsFrontliner
    ) {
        if (ref == null || !ref.isValid()) {
            return TacticalRole.UNKNOWN;
        }
        NPCEntity npc = store.getComponent(ref, NPCEntity.getComponentType());
        if (npc == null) {
            return treatPlayerAsFrontliner ? TacticalRole.PLAYER_FRONTLINER : TacticalRole.UNKNOWN;
        }
        return classifyRoleName(npc.getRoleName());
    }

    @Nonnull
    public static TacticalRole classifyRoleName(@Nullable String roleName) {
        if (roleName == null || roleName.isBlank()) {
            return TacticalRole.UNKNOWN;
        }
        if (roleName.contains("Companion_Vanguard")
            || roleName.contains("Companion_Melee")
            || roleName.contains("Companion_Bruiser")
            || roleName.contains("Companion_Barbarian")) {
            return TacticalRole.ALLY_VANGUARD;
        }
        if (roleName.contains("Companion_Archer")
            || roleName.contains("Companion_Scout")
            || roleName.contains("Companion_Mage")
            || roleName.contains("Companion_Wizard")) {
            return TacticalRole.ALLY_ARCHER;
        }
        if (roleName.contains("Companion_Support")
            || roleName.contains("Companion_Warden")
            || roleName.contains("Companion_Monk")
            || roleName.contains("Companion_Oracle")) {
            return TacticalRole.ALLY_SUPPORT;
        }
        if (roleName.contains("Enemy_Diver")) {
            return TacticalRole.ENEMY_DIVER;
        }
        if (roleName.contains("Enemy_Archer")) {
            return TacticalRole.ENEMY_ARCHER;
        }
        if (roleName.contains("Enemy_Zombie") || roleName.contains("Eye_Void")) {
            return TacticalRole.ENEMY_BRUISER;
        }
        return TacticalRole.UNKNOWN;
    }

    public static boolean isFrontliner(@Nonnull TacticalRole role) {
        return role == TacticalRole.PLAYER_FRONTLINER
            || role == TacticalRole.ALLY_VANGUARD
            || role == TacticalRole.ENEMY_BRUISER;
    }

    public static boolean isBackliner(@Nonnull TacticalRole role) {
        return role == TacticalRole.ALLY_ARCHER
            || role == TacticalRole.ALLY_SUPPORT
            || role == TacticalRole.ENEMY_ARCHER;
    }

    public static boolean isDiver(@Nonnull TacticalRole role) {
        return role == TacticalRole.ENEMY_DIVER;
    }

    public static boolean isMeleeAggroRoleName(@Nullable String roleName) {
        if (roleName == null || roleName.isBlank()) {
            return false;
        }
        return roleName.contains("Companion_Vanguard")
            || roleName.contains("Companion_Melee")
            || roleName.contains("Companion_Bruiser")
            || roleName.contains("Companion_Barbarian")
            || roleName.contains("Companion_Scout")
            || roleName.contains("Companion_Monk");
    }

    public static boolean isActivePartyMeleeAggroSource(
        @Nonnull Store<EntityStore> store,
        @Nonnull Ref<EntityStore> playerRef
    ) {
        Integer activeSlot = TrackedSummonStore.getActivePartySlot(playerRef);
        if (activeSlot == null) {
            return false;
        }

        Integer roleIndex = TrackedSummonStore.getPartyRoleIndex(playerRef, activeSlot);
        if (roleIndex != null && isMeleeAggroRoleName(NPCPlugin.get().getName(roleIndex))) {
            return true;
        }

        Ref<EntityStore> slotNpcRef = TrackedSummonStore.getNpcForUtilitySlot(playerRef, activeSlot);
        NPCEntity npc = slotNpcRef == null ? null : store.getComponent(slotNpcRef, NPCEntity.getComponentType());
        return npc != null && isMeleeAggroRoleName(npc.getRoleName());
    }

    public static boolean isActiveHeroHealer(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> playerRef) {
        TacticalRole activeRole = classifyActivePartyRole(store, playerRef);
        if (activeRole != TacticalRole.UNKNOWN) {
            return activeRole == TacticalRole.ALLY_SUPPORT;
        }

        Integer activeSlot = TrackedSummonStore.getActivePartySlot(playerRef);
        if (activeSlot == null) {
            return false;
        }
        Ref<EntityStore> npcRef = TrackedSummonStore.getNpcForUtilitySlot(playerRef, activeSlot);
        if (npcRef != null) {
            TacticalRole role = classifyRole(store, npcRef, false);
            return role == TacticalRole.ALLY_SUPPORT;
        }
        return false;
    }

    @Nonnull
    public static TacticalRole classifyActivePartyRole(
        @Nonnull Store<EntityStore> store,
        @Nonnull Ref<EntityStore> playerRef
    ) {
        Integer activeSlot = TrackedSummonStore.getActivePartySlot(playerRef);
        if (activeSlot == null) {
            return TacticalRole.UNKNOWN;
        }

        Integer roleIndex = TrackedSummonStore.getPartyRoleIndex(playerRef, activeSlot);
        if (roleIndex != null) {
            String roleName = NPCPlugin.get().getName(roleIndex);
            TacticalRole role = classifyRoleName(roleName);
            if (role != TacticalRole.UNKNOWN) {
                return role;
            }
        }

        Ref<EntityStore> slotNpcRef = TrackedSummonStore.getNpcForUtilitySlot(playerRef, activeSlot);
        if (slotNpcRef != null) {
            TacticalRole role = classifyRole(store, slotNpcRef, false);
            if (role != TacticalRole.UNKNOWN) {
                return role;
            }
        }

        Ref<EntityStore> activeNpcRef = TrackedSummonStore.getActiveNpcRef(playerRef);
        return activeNpcRef == null ? TacticalRole.UNKNOWN : classifyRole(store, activeNpcRef, false);
    }

    @Nonnull
    public static Map<Ref<EntityStore>, Integer> buildClaimCounts(
        @Nonnull Store<EntityStore> store,
        @Nonnull List<Ref<EntityStore>> attackers
    ) {
        Map<Ref<EntityStore>, Integer> counts = new HashMap<>();
        for (Ref<EntityStore> attacker : attackers) {
            Ref<EntityStore> target = getCurrentLockedTarget(store, attacker);
            if (!isAlive(store, target)) {
                continue;
            }
            counts.merge(target, 1, Integer::sum);
        }
        return counts;
    }

    public static void setTargetHostile(
        @Nonnull Store<EntityStore> store,
        @Nonnull Ref<EntityStore> attackerRef,
        @Nonnull Ref<EntityStore> targetRef
    ) {
        NPCEntity npc = store.getComponent(attackerRef, NPCEntity.getComponentType());
        if (npc == null) {
            return;
        }
        Role role = npc.getRole();
        if (role == null) {
            return;
        }

        role.setMarkedTarget(MarkedEntitySupport.DEFAULT_TARGET_SLOT, targetRef);
        role.getMarkedEntitySupport().setMarkedEntity(MarkedEntitySupport.DEFAULT_TARGET_SLOT, targetRef);
        WorldSupport support = role.getWorldSupport();
        ensureOverrideMemory(support);
        
        CombatTeam attackerTeam = classifyTeam(store, attackerRef);
        CombatTeam targetTeam = classifyTeam(store, targetRef);
        Attitude attitude = (attackerTeam != CombatTeam.UNKNOWN && attackerTeam == targetTeam) ? Attitude.FRIENDLY : Attitude.HOSTILE;
        
        support.overrideAttitude(targetRef, attitude, 60.0 * 60.0 * 24.0);
        support.requestNewPath();
    }

    public static void setTargetFriendly(
        @Nonnull Store<EntityStore> store,
        @Nonnull Ref<EntityStore> npcRef,
        @Nonnull Ref<EntityStore> targetRef
    ) {
        NPCEntity npc = store.getComponent(npcRef, NPCEntity.getComponentType());
        if (npc == null || npc.getRole() == null) {
            return;
        }
        WorldSupport support = npc.getRole().getWorldSupport();
        ensureOverrideMemory(support);
        support.overrideAttitude(targetRef, Attitude.FRIENDLY, 60.0 * 60.0 * 24.0);
    }

    public static void clearMarkedTarget(@Nonnull Store<EntityStore> store, @Nullable Ref<EntityStore> npcRef) {
        NPCEntity npc = store.getComponent(npcRef, NPCEntity.getComponentType());
        if (npc == null || npc.getRole() == null) {
            return;
        }
        npc.getRole().setMarkedTarget(MarkedEntitySupport.DEFAULT_TARGET_SLOT, null);
        npc.getRole().getMarkedEntitySupport().setMarkedEntity(MarkedEntitySupport.DEFAULT_TARGET_SLOT, null);
        npc.getRole().getWorldSupport().requestNewPath();
    }

    @Nullable
    public static Ref<EntityStore> getCurrentLockedTarget(
        @Nonnull Store<EntityStore> store,
        @Nonnull Ref<EntityStore> npcRef
    ) {
        NPCEntity npc = store.getComponent(npcRef, NPCEntity.getComponentType());
        if (npc == null || npc.getRole() == null) {
            return null;
        }
        return npc.getRole().getMarkedEntitySupport().getMarkedEntityRef(MarkedEntitySupport.DEFAULT_TARGET_SLOT);
    }

    public enum TacticalRole {
        PLAYER_FRONTLINER,
        ALLY_VANGUARD,
        ALLY_ARCHER,
        ALLY_SUPPORT,
        ENEMY_BRUISER,
        ENEMY_ARCHER,
        ENEMY_DIVER,
        UNKNOWN
    }

    @Nonnull
    public static CombatTeam classifyTeam(@Nonnull Store<EntityStore> store, @Nullable Ref<EntityStore> ref) {
        if (ref == null || !ref.isValid() || ref.getStore() != store) {
            return CombatTeam.UNKNOWN;
        }

        LevelSessionManager.ActiveRunSnapshot run = LevelSessionManager.get().getSnapshot();
        if (run == null || run.runWorldUuid() == null) {
            return CombatTeam.UNKNOWN;
        }

        UUID worldId = store.getExternalData().getWorld().getWorldConfig().getUuid();
        if (!run.runWorldUuid().equals(worldId)) {
            return CombatTeam.UNKNOWN;
        }

        LevelRunCombatStore.CombatWorldState combatState = LevelRunCombatStore.get().getWorld(worldId);
        if (combatState == null) {
            return CombatTeam.UNKNOWN;
        }

        PlayerRef owner = Universe.get().getPlayer(combatState.ownerPlayerUuid());
        if (owner == null) {
            return CombatTeam.UNKNOWN;
        }

        Ref<EntityStore> ownerRef = owner.getReference();
        for (UUID participantUuid : combatState.participantPlayerUuids()) {
            PlayerRef participant = Universe.get().getPlayer(participantUuid);
            Ref<EntityStore> participantRef = participant == null ? null : participant.getReference();
            if (isLocalRef(store, participantRef) && ref.equals(participantRef)) {
                return CombatTeam.PLAYER_SIDE;
            }
        }

        if (isLocalRef(store, ownerRef) && ownerRef.equals(ref)) {
            return CombatTeam.PLAYER_SIDE;
        }

        if (isLocalRef(store, ownerRef)) {
            for (Ref<EntityStore> companion : TrackedSummonStore.getTrackedSnapshot(ownerRef)) {
                if (ref.equals(companion)) {
                    return CombatTeam.PLAYER_SIDE;
                }
            }
        }

        for (Ref<EntityStore> enemy : combatState.activeEnemies()) {
            if (ref.equals(enemy)) {
                return CombatTeam.ENEMY_SIDE;
            }
        }

        // FALLBACK: Identify by role name if not in combatState list
        NPCEntity npc = store.getComponent(ref, NPCEntity.getComponentType());
        if (npc != null && npc.getRoleName() != null) {
            String roleName = npc.getRoleName();
            if (roleName.contains("Enemy_") || roleName.contains("Eye_Void")) {
                return CombatTeam.ENEMY_SIDE;
            }
        }

        return CombatTeam.UNKNOWN;
    }

    private static boolean isLocalRef(@Nonnull Store<EntityStore> store, @Nullable Ref<EntityStore> ref) {
        return ref != null && ref.isValid() && ref.getStore() == store;
    }

    public static boolean areAlliedInRun(
        @Nonnull Store<EntityStore> store,
        @Nullable Ref<EntityStore> left,
        @Nullable Ref<EntityStore> right
    ) {
        CombatTeam leftTeam = classifyTeam(store, left);
        CombatTeam rightTeam = classifyTeam(store, right);
        return leftTeam != CombatTeam.UNKNOWN && leftTeam == rightTeam;
    }

    public enum CombatTeam {
        PLAYER_SIDE,
        ENEMY_SIDE,
        UNKNOWN
    }

    private static void ensureOverrideMemory(@Nonnull WorldSupport support) {
        if (ATTITUDE_OVERRIDE_MEMORY_FIELD == null) {
            return;
        }
        try {
            Object value = ATTITUDE_OVERRIDE_MEMORY_FIELD.get(support);
            if (value == null) {
                ATTITUDE_OVERRIDE_MEMORY_FIELD.set(support, new Int2ObjectOpenHashMap<>());
            }
        } catch (IllegalAccessException ignored) {
        }
    }

    @Nullable
    private static Field resolveOverrideMemoryField() {
        try {
            Field field = WorldSupport.class.getDeclaredField("attitudeOverrideMemory");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }
}
