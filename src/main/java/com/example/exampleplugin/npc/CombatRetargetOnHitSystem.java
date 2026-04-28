package com.example.exampleplugin.npc;

import com.example.exampleplugin.levels.LevelRunCombatStore;
import com.example.exampleplugin.levels.LevelSessionManager;
import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.SystemGroup;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class CombatRetargetOnHitSystem extends DamageEventSystem {
    @Nullable
    @Override
    public SystemGroup<EntityStore> getGroup() {
        return DamageModule.get().getFilterDamageGroup();
    }

    @Nullable
    @Override
    public Query<EntityStore> getQuery() {
        return Archetype.empty();
    }

    @Override
    public void handle(
        int index,
        @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
        @Nonnull Store<EntityStore> store,
        @Nonnull CommandBuffer<EntityStore> commandBuffer,
        @Nonnull Damage damage
    ) {
        if (!(damage.getSource() instanceof Damage.EntitySource entitySource)) {
            return;
        }
        Ref<EntityStore> sourceRef = entitySource.getRef();
        if (!CombatTargetingUtil.isAlive(store, sourceRef)) {
            return;
        }

        LevelSessionManager.ActiveRunSnapshot run = LevelSessionManager.get().getSnapshot();
        if (run == null || run.runWorldUuid() == null) {
            return;
        }
        UUID worldId = store.getExternalData().getWorld().getWorldConfig().getUuid();
        if (!run.runWorldUuid().equals(worldId)) {
            return;
        }

        LevelRunCombatStore.CombatWorldState combatState = LevelRunCombatStore.get().getWorld(worldId);
        if (combatState == null) {
            return;
        }

        PlayerRef owner = Universe.get().getPlayer(combatState.ownerPlayerUuid());
        if (owner == null) {
            return;
        }
        Ref<EntityStore> ownerRef = owner.getReference();
        if (ownerRef == null || !ownerRef.isValid()) {
            return;
        }
        List<Ref<EntityStore>> players = buildLivingParticipantRefs(store, combatState);

        List<Ref<EntityStore>> enemies = new ArrayList<>();
        for (Ref<EntityStore> enemy : combatState.activeEnemies()) {
            if (CombatTargetingUtil.isAlive(store, enemy)) {
                enemies.add(enemy);
            }
        }

        List<Ref<EntityStore>> companions = new ArrayList<>();
        for (Ref<EntityStore> companion : TrackedSummonStore.getTrackedSnapshot(ownerRef)) {
            if (CombatTargetingUtil.isAlive(store, companion)) {
                companions.add(companion);
            }
        }

        Ref<EntityStore> targetRef = archetypeChunk.getReferenceTo(index);
        if (!CombatTargetingUtil.isAlive(store, targetRef)) {
            return;
        }

        boolean targetIsEnemy = containsRef(enemies, targetRef);
        boolean sourceIsEnemy = containsRef(enemies, sourceRef);
        boolean sourceIsCompanion = containsRef(companions, sourceRef);
        boolean targetIsCompanion = containsRef(companions, targetRef);
        boolean sourceIsOwner = sourceRef.equals(ownerRef);
        boolean targetIsOwner = targetRef.equals(ownerRef);
        boolean sourceIsPlayer = containsRef(players, sourceRef);
        boolean targetIsPlayer = containsRef(players, targetRef);

        if (targetIsEnemy && sourceIsEnemy) {
            CombatTargetingUtil.clearMarkedTarget(store, sourceRef);
            return;
        }

        if (targetIsEnemy && (sourceIsPlayer || sourceIsOwner || sourceIsCompanion)) {
            if (sourceIsPlayer || sourceIsOwner) {
                TrackedSummonStore.setTargetForActivePartySlot(sourceRef, targetRef, store);
            }
            return;
        }

        if (targetIsCompanion && sourceIsEnemy) {
            return;
        }

        if ((targetIsPlayer || targetIsOwner) && sourceIsEnemy) {
            return;
        }
    }

    private static boolean containsRef(@Nonnull List<Ref<EntityStore>> refs, @Nullable Ref<EntityStore> target) {
        if (target == null) {
            return false;
        }
        for (Ref<EntityStore> ref : refs) {
            if (ref != null && ref.equals(target)) {
                return true;
            }
        }
        return false;
    }

    @Nonnull
    private static List<Ref<EntityStore>> buildLivingParticipantRefs(
        @Nonnull Store<EntityStore> store,
        @Nonnull LevelRunCombatStore.CombatWorldState combatState
    ) {
        List<Ref<EntityStore>> playerRefs = new ArrayList<>();
        List<UUID> allPlayers = new ArrayList<>(combatState.participantPlayerUuids());
        if (!allPlayers.contains(combatState.ownerPlayerUuid())) {
            allPlayers.add(combatState.ownerPlayerUuid());
        }
        
        for (UUID playerUuid : allPlayers) {
            PlayerRef playerRef = Universe.get().getPlayer(playerUuid);
            if (playerRef == null) {
                continue;
            }
            Ref<EntityStore> ref = playerRef.getReference();
            if (CombatTargetingUtil.isAlive(store, ref)) {
                playerRefs.add(ref);
            }
        }
        return playerRefs;
    }
}
