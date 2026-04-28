package com.example.exampleplugin.npc;

import com.example.exampleplugin.levels.LevelRunCombatStore;
import com.example.exampleplugin.levels.LevelSessionManager;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.logger.HytaleLogger;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

public class CombatTargetAssignmentSystem extends TickingSystem<EntityStore> {
    private static final HytaleLogger LOGGER = HytaleLogger.get("Battleheart|CombatTarget");
    private long lastTickMs;

    @Override
    public void tick(float dt, int systemIndex, @Nonnull Store<EntityStore> store) {
        long now = System.currentTimeMillis();
        if (now - this.lastTickMs < 250L) {
            return;
        }
        this.lastTickMs = now;

        LevelSessionManager.ActiveRunSnapshot run = LevelSessionManager.get().getSnapshot();
        if (run == null || run.runWorldUuid() == null) {
            return;
        }

        World world = store.getExternalData().getWorld();
        UUID worldId = world.getWorldConfig().getUuid();
        if (!run.runWorldUuid().equals(worldId)) {
            return;
        }

        LevelRunCombatStore.CombatWorldState combatState = LevelRunCombatStore.get().getWorld(worldId);
        if (combatState == null) {
            return;
        }
        List<Ref<EntityStore>> enemies = new ArrayList<>();
        for (Ref<EntityStore> enemy : combatState.activeEnemies()) {
            if (CombatTargetingUtil.isLiveEnemyTarget(store, enemy)) {
                enemies.add(enemy);
            }
        }
        if (enemies.isEmpty()) {
            return;
        }

        for (Ref<EntityStore> enemy : enemies) {
            keepEnemiesFriendly(store, enemy, enemies);
        }

        autoTargetForCompanions(store, combatState, enemies);
    }

    private static void autoTargetForCompanions(
        @Nonnull Store<EntityStore> store,
        @Nonnull LevelRunCombatStore.CombatWorldState combatState,
        @Nonnull List<Ref<EntityStore>> enemies
    ) {
        PlayerRef owner = Universe.get().getPlayer(combatState.ownerPlayerUuid());
        if (owner == null) {
            return;
        }

        Ref<EntityStore> ownerRef = owner.getReference();
        if (ownerRef == null || !ownerRef.isValid()) {
            return;
        }

        List<Ref<EntityStore>> companions = TrackedSummonStore.getTrackedSnapshot(ownerRef);
        Ref<EntityStore> activeNpc = TrackedSummonStore.getActiveNpcRef(ownerRef);

        for (int i = 0; i < companions.size(); i++) {
            Ref<EntityStore> companion = companions.get(i);
            if (companion == null || !companion.isValid() || companion.getStore() != store) {
                continue;
            }
            
            if (!CombatTargetingUtil.isAlive(store, companion)) {
                continue;
            }

            Ref<EntityStore> currentTarget = CombatTargetingUtil.getCurrentLockedTarget(store, companion);
            if (!CombatTargetingUtil.isAlive(store, currentTarget)) {
                LOGGER.at(Level.INFO).log("autoTargetForCompanions: companion %s needs target (enemies: %s)", companion, enemies.size());
                assignNewAutoTarget(store, companion, enemies, ownerRef, companions);
            }
        }

        // Also ensure the active hero (the carrier) has an auto-target if they don't have one
        if (activeNpc != null && activeNpc.isValid() && activeNpc.getStore() == store && CombatTargetingUtil.isAlive(store, activeNpc)) {
            Ref<EntityStore> currentTarget = CombatTargetingUtil.getCurrentLockedTarget(store, activeNpc);
            if (!CombatTargetingUtil.isAlive(store, currentTarget)) {
                LOGGER.at(Level.INFO).log("autoTargetForCompanions: active hero %s needs target (enemies: %s)", activeNpc, enemies.size());
                assignNewAutoTarget(store, activeNpc, enemies, ownerRef, companions);
            }
        }
    }

    private static void assignNewAutoTarget(
        @Nonnull Store<EntityStore> store,
        @Nonnull Ref<EntityStore> companion,
        @Nonnull List<Ref<EntityStore>> enemies,
        @Nonnull Ref<EntityStore> ownerRef,
        @Nonnull List<Ref<EntityStore>> companions
    ) {
        CombatTargetingUtil.TacticalRole role = CombatTargetingUtil.classifyRole(store, companion, false);
        if (role == CombatTargetingUtil.TacticalRole.ALLY_SUPPORT) {
            // Healers target allies
            List<Ref<EntityStore>> allies = new ArrayList<>();
            allies.add(ownerRef);
            allies.addAll(companions);
            
            Ref<EntityStore> healTarget = CombatTargetingUtil.chooseLowestHealthTarget(store, allies);
            LOGGER.at(Level.INFO).log("assignNewAutoTarget: healer %s picked ally target %s", companion, healTarget);
            if (healTarget != null) {
                CombatTargetingUtil.setTargetHostile(store, companion, healTarget); // setTargetHostile actually sets the marked target
            }
        } else {
            // Others target enemies
            Ref<EntityStore> enemyTarget = CombatTargetingUtil.chooseRandomAlive(store, enemies);
            LOGGER.at(Level.INFO).log("assignNewAutoTarget: combatant %s picked enemy target %s", companion, enemyTarget);
            if (enemyTarget != null) {
                CombatTargetingUtil.setTargetHostile(store, companion, enemyTarget);
            }
        }
    }

    private static void keepEnemiesFriendly(
        @Nonnull Store<EntityStore> store,
        @Nonnull Ref<EntityStore> enemy,
        @Nonnull List<Ref<EntityStore>> enemies
    ) {
        for (Ref<EntityStore> otherEnemy : enemies) {
            if (otherEnemy == null || otherEnemy.equals(enemy) || !CombatTargetingUtil.isAlive(store, otherEnemy)) {
                continue;
            }
            CombatTargetingUtil.setTargetFriendly(store, enemy, otherEnemy);
        }
    }

}
