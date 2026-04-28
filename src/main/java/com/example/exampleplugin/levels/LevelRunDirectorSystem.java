package com.example.exampleplugin.levels;

import com.example.exampleplugin.hud.VictoryHud;
import com.example.exampleplugin.levels.LevelSessionManager.ActiveRunSnapshot;
import com.example.exampleplugin.levels.model.EnemySpawnDefinition;
import com.example.exampleplugin.levels.model.LevelDefinition;
import com.example.exampleplugin.levels.model.TransformData;
import com.example.exampleplugin.levels.model.Vector3Data;
import com.example.exampleplugin.levels.model.WaveDefinition;
import com.example.exampleplugin.npc.CombatTargetingUtil;
import com.example.exampleplugin.npc.CompanionCombatSettings;
import com.example.exampleplugin.npc.EntityHealthUtil;
import com.example.exampleplugin.npc.TrackedSummonStore;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.function.consumer.TriConsumer;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.server.core.command.system.CommandManager;
import com.hypixel.hytale.server.core.entity.Frozen;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.asset.type.attitude.Attitude;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.asset.builder.BuilderInfo;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.WorldSupport;
import it.unimi.dsi.fastutil.Pair;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.lang.reflect.Field;
import java.util.logging.Level;

public class LevelRunDirectorSystem extends TickingSystem<EntityStore> {
    private static final HytaleLogger LOGGER = HytaleLogger.get("HyGen|RunDirector");
    private static final long RUN_BOOTSTRAP_DELAY_MS = 1000L;
    @Nullable
    private static final Field ATTITUDE_OVERRIDE_MEMORY_FIELD = resolveOverrideMemoryField();
    private static final ConcurrentHashMap.KeySetView<UUID, Boolean> VICTORY_ACTIVE_WORLDS = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<UUID, RuntimeState> stateByWorld = new ConcurrentHashMap<>();
    private long lastTickMs;

    @Override
    public void tick(float dt, int systemIndex, @Nonnull Store<EntityStore> store) {
        long now = System.currentTimeMillis();
        if (now - this.lastTickMs < 250L) {
            return;
        }
        this.lastTickMs = now;

        LevelSessionManager.ActiveRunSnapshot snapshot = LevelSessionManager.get().getSnapshot();
        if (snapshot == null || snapshot.runWorldUuid() == null) {
            this.stateByWorld.remove(store.getExternalData().getWorld().getWorldConfig().getUuid());
            this.stateByWorld.clear();
            VICTORY_ACTIVE_WORLDS.clear();
            LevelRunCombatStore.get().clearAll();
            return;
        }

        World world = store.getExternalData().getWorld();
        UUID worldId = world.getWorldConfig().getUuid();
        if (!snapshot.runWorldUuid().equals(worldId)) {
            this.stateByWorld.remove(worldId);
            VICTORY_ACTIVE_WORLDS.remove(worldId);
            LevelRunCombatStore.get().clearWorld(worldId);
            return;
        }

        RuntimeState state = this.stateByWorld.computeIfAbsent(worldId, ignored -> new RuntimeState(now));
        LevelDefinition level = LevelConfigStore.get().getLevelById(snapshot.levelId());
        
        if (snapshot.isEndless()) {
            if (level == null) {
                level = new LevelDefinition();
                level.levelId = snapshot.levelId();
            } else {
                LevelDefinition clone = new LevelDefinition();
                clone.levelId = level.levelId;
                clone.levelName = level.levelName;
                clone.mapWorldName = level.mapWorldName;
                clone.mapDisplayName = level.mapDisplayName;
                clone.overviewCamera = level.overviewCamera;
                clone.orderIndex = level.orderIndex;
                clone.playerSpawn = level.playerSpawn;
                clone.fallbackReturnSpawn = level.fallbackReturnSpawn;
                clone.enemySpawnLocations = level.enemySpawnLocations;
                clone.waves = level.waves;
                level = clone;
            }
            level.isEndlessAi = true;
        }

        if (level == null) {
            return;
        }

        PlayerRef owner = Universe.get().getPlayer(snapshot.ownerPlayerUuid());
        if (owner == null) {
            return;
        }

        maybeConfigureMatchTime(owner, world, state);
        maybeSpawnCompanions(owner, store, world, snapshot, state, now);
        if (tryFinishDefeatedRun(store, world, owner, state)) {
            return;
        }
        tickWaves(level, store, world, owner, state, now);
        LevelRunCombatStore.get().updateWorld(worldId, snapshot.ownerPlayerUuid(), snapshot.participantPlayerUuids(), state.activeEnemyRefs);
    }

    private static boolean tryFinishDefeatedRun(
        @Nonnull Store<EntityStore> store,
        @Nonnull World world,
        @Nonnull PlayerRef owner,
        @Nonnull RuntimeState state
    ) {
        if (!state.companionsSpawned || state.victoryStarted || state.defeatFinishTriggered) {
            return false;
        }

        Ref<EntityStore> ownerRef = owner.getReference();
        if (ownerRef == null || !ownerRef.isValid() || ownerRef.getStore() != store) {
            return false;
        }

        if (!buildLivingPartyTargets(store, ownerRef, state).isEmpty()) {
            return false;
        }

        state.defeatFinishTriggered = true;
        standDownPlayerSideCombat(store, ownerRef);
        owner.sendMessage(com.hypixel.hytale.server.core.Message.raw("Defeat. Returning to main menu..."));
        LevelSessionManager.get().finishActiveRun(false);
        return true;
    }

    private static void maybeConfigureMatchTime(
        @Nonnull PlayerRef owner,
        @Nonnull World world,
        @Nonnull RuntimeState state
    ) {
        if (state.timeConfigured) {
            return;
        }

        UUID ownerWorld = owner.getWorldUuid();
        if (ownerWorld == null || !ownerWorld.equals(world.getWorldConfig().getUuid())) {
            return;
        }

        state.timeConfigured = true;
        CommandManager.get().handleCommand(owner, "time set 12")
            .thenCompose(unused -> CommandManager.get().handleCommand(owner, "time pause"));
    }

    private void maybeSpawnCompanions(
        @Nonnull PlayerRef owner,
        @Nonnull Store<EntityStore> store,
        @Nonnull World world,
        @Nonnull ActiveRunSnapshot snapshot,
        @Nonnull RuntimeState state,
        long now
    ) {
        if (now < state.bootstrapReadyAtMs) {
            return;
        }
        if (state.companionsSpawned || now < state.nextCompanionSpawnAttemptMs) {
            return;
        }
        if (LevelSessionManager.get().getCompanionSpawnCount() <= 0) {
            state.companionsSpawned = allParticipantsInRunWorld(snapshot, world);
            if (!state.companionsSpawned) {
                state.nextCompanionSpawnAttemptMs = now + 250L;
            }
            return;
        }
        UUID ownerWorld = owner.getWorldUuid();
        if (ownerWorld == null || !ownerWorld.equals(world.getWorldConfig().getUuid())) {
            state.nextCompanionSpawnAttemptMs = now + 250L;
            return;
        }

        Ref<EntityStore> ownerRef = owner.getReference();
        if (ownerRef == null || !ownerRef.isValid()) {
            state.nextCompanionSpawnAttemptMs = now + 250L;
            return;
        }
        if (ownerRef.getStore() != store) {
            state.nextCompanionSpawnAttemptMs = now + 250L;
            return;
        }

        List<Ref<EntityStore>> tracked = TrackedSummonStore.getTrackedSnapshot(ownerRef);
        if (!tracked.isEmpty()) {
            state.companionsSpawned = true;
            return;
        }

        state.companionsSpawnAttempted = true;
        CommandManager.get().handleCommand(owner, "summonnpc3");
        state.nextCompanionSpawnAttemptMs = now + 500L;
    }

    private static boolean allParticipantsInRunWorld(@Nonnull ActiveRunSnapshot snapshot, @Nonnull World world) {
        UUID worldId = world.getWorldConfig().getUuid();
        for (UUID playerUuid : snapshot.participantPlayerUuids()) {
            PlayerRef playerRef = Universe.get().getPlayer(playerUuid);
            if (playerRef == null || playerRef.getWorldUuid() == null || !playerRef.getWorldUuid().equals(worldId)) {
                return false;
            }
        }
        return true;
    }

    private void tickWaves(
        @Nonnull LevelDefinition level,
        @Nonnull Store<EntityStore> store,
        @Nonnull World world,
        @Nonnull PlayerRef owner,
        @Nonnull RuntimeState state,
        long now
    ) {
        boolean isEndless = Boolean.TRUE.equals(level.isEndlessAi);
        if (!isEndless && (level.waves == null || level.waves.isEmpty())) {
            return;
        }

        pruneDeadEnemies(store, state.activeEnemyRefs);
        if (!state.activeEnemyRefs.isEmpty()) {
            Ref<EntityStore> ownerRef = owner.getReference();
            if (ownerRef != null && ownerRef.isValid()) {
                refreshEnemyAggro(store, world, ownerRef, state);
            }
        }

        if (!state.companionsSpawned) {
            return;
        }

        if (state.currentWaveIndex < 0) {
            if (isEndless) {
                tryStartEndlessWave(level, 0, store, world, owner, state, now);
            } else {
                tryStartWave(level, 0, store, world, owner, state, now);
            }
            return;
        }

        if (!state.activeEnemyRefs.isEmpty()) {
            return;
        }

        if (now < state.nextWaveStartMs) {
            return;
        }

        int nextWave = state.currentWaveIndex + 1;
        if (!isEndless && nextWave >= level.waves.size()) {
            if (!state.victoryStarted) {
                state.victoryStarted = true;
                VICTORY_ACTIVE_WORLDS.add(world.getWorldConfig().getUuid());
                state.victoryFinishAtMs = now + 2_000L;
                Ref<EntityStore> ownerRef = owner.getReference();
                if (ownerRef != null && ownerRef.isValid()) {
                    standDownPlayerSideCombat(store, ownerRef);
                }
                owner.sendMessage(com.hypixel.hytale.server.core.Message.raw("All waves defeated. Victory!"));
                LevelProgressStore.get().completeLevel(owner.getUuid(), level.levelId);
                showVictoryOverlay(owner);
            }
            if (!state.victoryFinishTriggered && now >= state.victoryFinishAtMs) {
                state.victoryFinishTriggered = true;
                LevelSessionManager.get().finishActiveRun(true);
            }
            return;
        }
        
        if (isEndless) {
            tryStartEndlessWave(level, nextWave, store, world, owner, state, now);
        } else {
            tryStartWave(level, nextWave, store, world, owner, state, now);
        }
    }

    public static boolean isVictoryOverlayActive(@Nonnull UUID worldId) {
        return VICTORY_ACTIVE_WORLDS.contains(worldId);
    }

    public static void clearVictoryOverlayActive(@Nonnull UUID worldId) {
        VICTORY_ACTIVE_WORLDS.remove(worldId);
    }

    private void tryStartWave(
        @Nonnull LevelDefinition level,
        int waveIndex,
        @Nonnull Store<EntityStore> store,
        @Nonnull World world,
        @Nonnull PlayerRef owner,
        @Nonnull RuntimeState state,
        long now
    ) {
        WaveStartResult result = spawnWave(level, waveIndex, store, world, owner);
        if (!result.spawnedRefs.isEmpty() || result.expectedCount == 0) {
            state.currentWaveIndex = waveIndex;
            state.activeEnemyRefs.clear();
            state.activeEnemyRefs.addAll(result.spawnedRefs);
            state.assignedTargetByEnemy.clear();
            assignEnemyTargets(store, owner.getReference(), state);
            if (owner.getReference() != null && owner.getReference().isValid()) {
                refreshEnemyAggro(store, world, owner.getReference(), state);
            }
            int delay = level.waves.get(waveIndex).interWaveDelayMs == null ? 1000 : Math.max(0, level.waves.get(waveIndex).interWaveDelayMs);
            state.nextWaveStartMs = now + delay;
            state.failedWaveSpawnNoticeAtMs = 0L;
            return;
        }

        state.nextWaveStartMs = now + 1000L;
        if (now - state.failedWaveSpawnNoticeAtMs >= 2000L) {
            state.failedWaveSpawnNoticeAtMs = now;
            owner.sendMessage(com.hypixel.hytale.server.core.Message.raw(
                "Wave " + (waveIndex + 1) + " failed to spawn (" + result.expectedCount + " expected). Retrying..."
            ));
        }
    }

    private void tryStartEndlessWave(
        @Nonnull LevelDefinition level,
        int waveIndex,
        @Nonnull Store<EntityStore> store,
        @Nonnull World world,
        @Nonnull PlayerRef owner,
        @Nonnull RuntimeState state,
        long now
    ) {
        WaveDefinition wave = new WaveDefinition();
        wave.waveNumber = waveIndex;
        wave.bossWave = false;
        wave.interWaveDelayMs = 2000;
        wave.enemies = new ArrayList<>();
        
        String[] pools = {"Archer", "Barbarian", "Mage", "Monk", "Vanguard", "Warden", "Wizard"};
        for (int i = 0; i < 10; i++) {
            EnemySpawnDefinition def = new EnemySpawnDefinition();
            def.npcRoleId = pools[java.util.concurrent.ThreadLocalRandom.current().nextInt(pools.length)];
            def.count = 1;
            wave.enemies.add(def);
        }
        
        WaveStartResult result = spawnWaveDefinition(level, wave, waveIndex, store, world, owner);
        if (!result.spawnedRefs.isEmpty() || result.expectedCount == 0) {
            state.currentWaveIndex = waveIndex;
            state.activeEnemyRefs.clear();
            state.activeEnemyRefs.addAll(result.spawnedRefs);
            state.assignedTargetByEnemy.clear();
            assignEnemyTargets(store, owner.getReference(), state);
            if (owner.getReference() != null && owner.getReference().isValid()) {
                refreshEnemyAggro(store, world, owner.getReference(), state);
            }
            state.nextWaveStartMs = now + 2000;
            state.failedWaveSpawnNoticeAtMs = 0L;
            return;
        }

        state.nextWaveStartMs = now + 1000L;
        if (now - state.failedWaveSpawnNoticeAtMs >= 2000L) {
            state.failedWaveSpawnNoticeAtMs = now;
            owner.sendMessage(com.hypixel.hytale.server.core.Message.raw(
                "Endless Wave " + (waveIndex + 1) + " failed to spawn. Retrying..."
            ));
        }
    }

    @Nonnull
    private WaveStartResult spawnWave(
        @Nonnull LevelDefinition level,
        int waveIndex,
        @Nonnull Store<EntityStore> store,
        @Nonnull World world,
        @Nonnull PlayerRef owner
    ) {
        WaveDefinition wave = level.waves.get(waveIndex);
        return spawnWaveDefinition(level, wave, waveIndex, store, world, owner);
    }

    @Nonnull
    private WaveStartResult spawnWaveDefinition(
        @Nonnull LevelDefinition level,
        @Nonnull WaveDefinition wave,
        int waveIndex,
        @Nonnull Store<EntityStore> store,
        @Nonnull World world,
        @Nonnull PlayerRef owner
    ) {
        List<Ref<EntityStore>> spawned = new ArrayList<>();
        List<EnemySpawnDefinition> enemies = wave.enemies == null ? List.of() : wave.enemies;
        Ref<EntityStore> ownerRef = owner.getReference();
        Vector3d ownerPos = getOwnerPosition(ownerRef);
        int expected = 0;
        for (EnemySpawnDefinition enemy : enemies) {
            int count = enemy != null && enemy.count != null && enemy.count > 0 ? enemy.count : 1;
            expected += count;
            int roleIndex = resolveRoleIndex(enemy == null ? null : enemy.npcRoleId);
            if (roleIndex < 0) {
                continue;
            }

            TransformData spawnTransform = enemy != null ? enemy.spawn : null;
            for (int i = 0; i < count; i++) {
                Vector3d spawnPos = resolveSpawnPosition(level, spawnTransform, ownerPos, i);
                Vector3f rotation = new Vector3f(
                    spawnTransform == null ? 0.0f : spawnTransform.roll,
                    spawnTransform == null ? 0.0f : spawnTransform.yaw,
                    spawnTransform == null ? 0.0f : spawnTransform.pitch
                );

                float modelScale = modelScaleForEnemy(enemy);
                TriConsumer<NPCEntity, Ref<EntityStore>, Store<EntityStore>> postSpawn = (npcEntity, npcRef, entityStore) -> {
                    npcEntity.setInitialModelScale(modelScale);
                };
                Pair<Ref<EntityStore>, NPCEntity> pair = NPCPlugin.get().spawnEntity(store, roleIndex, spawnPos, rotation, null, postSpawn);
                if (pair != null && pair.first() != null && pair.first().isValid()) {
                    Ref<EntityStore> npcRef = pair.first();
                    if (store.getComponent(npcRef, Frozen.getComponentType()) != null) {
                        store.removeComponent(npcRef, Frozen.getComponentType());
                    }
                    EntityHealthUtil.fillHealthToMax(store, npcRef);
                    spawned.add(npcRef);
                }
            }
        }

        if (!spawned.isEmpty()) {
            owner.sendMessage(com.hypixel.hytale.server.core.Message.raw(
                "Wave " + (waveIndex + 1) + " started. Enemies: " + spawned.size()
            ));
        } else if (expected == 0) {
            owner.sendMessage(com.hypixel.hytale.server.core.Message.raw(
                "Wave " + (waveIndex + 1) + " has no configured enemies. Skipping."
            ));
        }
        return new WaveStartResult(expected, spawned);
    }

    private static float modelScaleForEnemy(@Nullable EnemySpawnDefinition enemy) {
        if (enemy != null && "Eye_Void".equals(enemy.npcRoleId)) {
            return 2.0f;
        }
        return 1.0f;
    }

    private static void pruneDeadEnemies(@Nonnull Store<EntityStore> store, @Nonnull List<Ref<EntityStore>> refs) {
        refs.removeIf(ref -> {
            if (ref == null || !ref.isValid()) {
                return true;
            }
            if (ref.getStore() != store) {
                return true;
            }
            return store.getComponent(ref, DeathComponent.getComponentType()) != null;
        });
    }

    private static void assignEnemyTargets(
        @Nonnull Store<EntityStore> store,
        @Nullable Ref<EntityStore> ownerRef,
        @Nonnull RuntimeState state
    ) {
        if (ownerRef == null || !ownerRef.isValid()) {
            return;
        }

        List<Ref<EntityStore>> livingTargets = buildLivingPartyTargets(store, ownerRef, state);
        if (livingTargets.isEmpty()) {
            return;
        }

        int enemyCount = state.activeEnemyRefs.size();
        for (int i = 0; i < enemyCount; i++) {
            Ref<EntityStore> enemyRef = state.activeEnemyRefs.get(i);
            if (enemyRef == null || !enemyRef.isValid()) {
                continue;
            }
            int targetIndex = Math.min(livingTargets.size() - 1, (i * livingTargets.size()) / Math.max(1, enemyCount));
            state.assignedTargetByEnemy.put(enemyRef, livingTargets.get(targetIndex));
        }
    }

    private static int resolveRoleIndex(@Nullable String configuredRole) {
        NPCPlugin plugin = NPCPlugin.get();
        Integer direct = tryRole(plugin, configuredRole);
        if (direct != null) {
            return direct;
        }
        Integer zombie = tryRole(plugin, "Zombie");
        if (zombie != null) {
            return zombie;
        }
        return -1;
    }

    @Nullable
    private static Integer tryRole(@Nonnull NPCPlugin plugin, @Nullable String roleName) {
        if (roleName == null || roleName.isBlank()) {
            return null;
        }
        int idx = plugin.getIndex(roleName);
        if (idx < 0) {
            return null;
        }
        BuilderInfo info = plugin.getRoleBuilderInfo(idx);
        if (info == null || !info.getBuilder().isSpawnable()) {
            return null;
        }
        return idx;
    }

    private static void showVictoryOverlay(@Nonnull PlayerRef owner) {
        // Prevent the editor HUD from replacing the victory overlay.
        LevelEditorManager.get().setHudEnabled(owner.getUuid(), false);

        Ref<EntityStore> ownerRef = owner.getReference();
        if (ownerRef == null || !ownerRef.isValid()) {
            return;
        }
        Store<EntityStore> store = ownerRef.getStore();
        Player player = store.getComponent(ownerRef, Player.getComponentType());
        if (player == null) {
            return;
        }
        VictoryHud hud = new VictoryHud(owner);
        hud.setVisible(true);
        player.getHudManager().setCustomHud(owner, hud);
    }

    @Nullable
    private static Vector3d getOwnerPosition(@Nullable Ref<EntityStore> ownerRef) {
        if (ownerRef == null || !ownerRef.isValid()) {
            return null;
        }
        TransformComponent t = ownerRef.getStore().getComponent(ownerRef, TransformComponent.getComponentType());
        return t == null ? null : new Vector3d(t.getPosition());
    }

    @Nonnull
    private static Vector3d resolveSpawnPosition(@Nonnull LevelDefinition level, @Nullable TransformData spawn, @Nullable Vector3d ownerPos, int index) {
        if (spawn != null && spawn.position != null) {
            Vector3Data p = spawn.position;
            return new Vector3d(p.x + (index * 0.6), p.y, p.z + (index * 0.6));
        }
        if (ownerPos != null) {
            double angle = (index * Math.PI * 2) / 10.0;
            return new Vector3d(ownerPos.x + Math.cos(angle) * 8.0, ownerPos.y + 0.5, ownerPos.z + Math.sin(angle) * 8.0);
        }
        if (level.enemySpawnLocations != null && !level.enemySpawnLocations.isEmpty()) {
            Vector3Data p = level.enemySpawnLocations.get(index % level.enemySpawnLocations.size());
            return new Vector3d(p.x, p.y, p.z);
        }
        if (level.playerSpawn != null && level.playerSpawn.position != null) {
            Vector3Data p = level.playerSpawn.position;
            return new Vector3d(p.x + 5.0 + (index * 0.6), p.y, p.z);
        }
        return new Vector3d(0.0 + index, 100.0, 0.0);
    }

    private static void configureZombieAggro(
        @Nonnull Store<EntityStore> store,
        @Nonnull World world,
        @Nonnull Ref<EntityStore> ownerRef,
        @Nonnull RuntimeState state,
        @Nonnull Ref<EntityStore> zombieRef,
        @Nonnull Ref<EntityStore> desiredTarget
    ) {
        NPCEntity zombie = store.getComponent(zombieRef, NPCEntity.getComponentType());
        if (zombie == null) {
            return;
        }
        Role role = zombie.getRole();
        if (role == null) {
            return;
        }
        WorldSupport support = role.getWorldSupport();
        ensureOverrideMemory(support);
        List<Ref<EntityStore>> playerSideTargets = buildPlayerSideTargets(store, world, ownerRef, state);

        Ref<EntityStore> lockedTarget = CombatTargetingUtil.getCurrentLockedTarget(store, zombieRef);
        if (!desiredTarget.equals(lockedTarget)) {
            CombatTargetingUtil.setTargetHostile(store, zombieRef, desiredTarget);
            lockedTarget = desiredTarget;
        }

        Ref<EntityStore> hostileTarget = lockedTarget;
        if (hostileTarget == null) {
            return;
        }

        for (Ref<EntityStore> target : playerSideTargets) {
            if (target == null || !target.isValid() || target.getStore() != store) {
                continue;
            }
            support.overrideAttitude(target, target.equals(hostileTarget) ? Attitude.HOSTILE : Attitude.FRIENDLY, 60.0 * 60.0 * 24.0);
        }
        support.requestNewPath();
    }

    @Nonnull
    private static List<Ref<EntityStore>> buildPlayerSideTargets(
        @Nonnull Store<EntityStore> store,
        @Nonnull World world,
        @Nonnull Ref<EntityStore> ownerRef,
        @Nullable RuntimeState state
    ) {
        List<Ref<EntityStore>> targets = new ArrayList<>();
        
        // Add all players in the world
        for (PlayerRef playerRef : world.getPlayerRefs()) {
            Ref<EntityStore> playerEntityRef = playerRef.getReference();
            if (isCombatAlive(store, playerEntityRef) && !containsRef(targets, playerEntityRef)) {
                targets.add(playerEntityRef);
            }
        }

        // Add all companions
        List<Ref<EntityStore>> companions = TrackedSummonStore.getTrackedSnapshot(ownerRef);
        for (Ref<EntityStore> companion : companions) {
            if (isCombatAlive(store, companion) && !containsRef(targets, companion)) {
                targets.add(companion);
            }
        }
        return targets;
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

    public static void standDownPlayerSideCombat(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ownerRef) {
        CompanionCombatSettings.setCombatEnabled(false);

        List<Ref<EntityStore>> party = new ArrayList<>();
        party.add(ownerRef);
        for (Ref<EntityStore> companion : TrackedSummonStore.getTrackedSnapshot(ownerRef)) {
            if (companion != null && companion.isValid() && companion.getStore() == store) {
                party.add(companion);
            }
        }

        for (Ref<EntityStore> member : party) {
            if (member == null || !member.isValid() || member.getStore() != store) {
                continue;
            }
            CombatTargetingUtil.clearMarkedTarget(store, member);

            NPCEntity npc = store.getComponent(member, NPCEntity.getComponentType());
            if (npc == null || npc.getRole() == null) {
                continue;
            }

            WorldSupport support = npc.getRole().getWorldSupport();
            ensureOverrideMemory(support);
            for (Ref<EntityStore> ally : party) {
                if (ally != null && ally.isValid() && ally.getStore() == store && !ally.equals(member)) {
                    support.overrideAttitude(ally, Attitude.FRIENDLY, 60.0 * 60.0 * 24.0);
                }
            }
            support.requestNewPath();
        }
    }

    private static void refreshEnemyAggro(
        @Nonnull Store<EntityStore> store,
        @Nonnull World world,
        @Nonnull Ref<EntityStore> ownerRef,
        @Nonnull RuntimeState state
    ) {
        List<Ref<EntityStore>> livingTargets = buildLivingPartyTargets(store, ownerRef, state);
        if (livingTargets.isEmpty()) {
            return;
        }

        for (int i = 0; i < state.activeEnemyRefs.size(); i++) {
            Ref<EntityStore> enemy = state.activeEnemyRefs.get(i);
            if (enemy == null || !enemy.isValid()) {
                continue;
            }
            Ref<EntityStore> assignedTarget = state.assignedTargetByEnemy.get(enemy);
            if (!CombatTargetingUtil.isAlive(store, assignedTarget)) {
                int targetIndex = Math.min(livingTargets.size() - 1, (i * livingTargets.size()) / Math.max(1, state.activeEnemyRefs.size()));
                assignedTarget = livingTargets.get(targetIndex);
                state.assignedTargetByEnemy.put(enemy, assignedTarget);
            }
            configureZombieAggro(store, world, ownerRef, state, enemy, assignedTarget);
        }
    }

    @Nonnull
    private static List<Ref<EntityStore>> buildLivingPartyTargets(
        @Nonnull Store<EntityStore> store,
        @Nonnull Ref<EntityStore> ownerRef,
        @Nonnull RuntimeState state
    ) {
        List<Ref<EntityStore>> targets = new ArrayList<>(3);
        for (int slot = 1; slot <= 3; slot++) {
            Ref<EntityStore> target = resolveLivingPartySlotTarget(store, ownerRef, slot, state);
            if (target != null && !containsRef(targets, target)) {
                targets.add(target);
            }
        }
        return targets;
    }

    @Nullable
    private static Ref<EntityStore> resolveLivingPartySlotTarget(
        @Nonnull Store<EntityStore> store,
        @Nonnull Ref<EntityStore> ownerRef,
        int slot,
        @Nonnull RuntimeState state
    ) {
        Ref<EntityStore> playerRef = TrackedSummonStore.getPlayerForPartySlot(ownerRef, slot);
        if (isCombatAlive(store, playerRef)) {
            return playerRef;
        }

        Ref<EntityStore> npcRef = TrackedSummonStore.getNpcForUtilitySlot(ownerRef, slot);
        if (isCombatAlive(store, npcRef)) {
            return npcRef;
        }
        return null;
    }

    private static boolean isCombatAlive(@Nonnull Store<EntityStore> store, @Nullable Ref<EntityStore> ref) {
        if (ref == null || !ref.isValid() || ref.getStore() != store) {
            return false;
        }
        if (store.getComponent(ref, DeathComponent.getComponentType()) != null) {
            return false;
        }
        EntityStatMap stats = store.getComponent(ref, EntityStatMap.getComponentType());
        if (stats == null) {
            return true;
        }
        EntityStatValue health = stats.get(DefaultEntityStatTypes.getHealth());
        return health == null || health.get() > 0f;
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

    private static final class RuntimeState {
        private final List<Ref<EntityStore>> activeEnemyRefs = new ArrayList<>();
        private int currentWaveIndex = -1;
        private long nextWaveStartMs;
        private boolean victoryStarted;
        private boolean victoryFinishTriggered;
        private boolean defeatFinishTriggered;
        private long victoryFinishAtMs;
        private boolean companionsSpawned;
        private boolean companionsSpawnAttempted;
        private boolean timeConfigured;
        private long nextCompanionSpawnAttemptMs;
        private long failedWaveSpawnNoticeAtMs;
        private final Map<Ref<EntityStore>, Ref<EntityStore>> assignedTargetByEnemy = new HashMap<>();
        private final long bootstrapReadyAtMs;

        private RuntimeState(long now) {
            this.nextWaveStartMs = now;
            this.nextCompanionSpawnAttemptMs = now;
            this.bootstrapReadyAtMs = now + RUN_BOOTSTRAP_DELAY_MS;
        }
    }

    private static final class WaveStartResult {
        private final int expectedCount;
        @Nonnull
        private final List<Ref<EntityStore>> spawnedRefs;

        private WaveStartResult(int expectedCount, @Nonnull List<Ref<EntityStore>> spawnedRefs) {
            this.expectedCount = expectedCount;
            this.spawnedRefs = spawnedRefs;
        }
    }
}
