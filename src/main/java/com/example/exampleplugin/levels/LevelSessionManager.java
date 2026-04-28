package com.example.exampleplugin.levels;

import com.example.exampleplugin.hud.HudUtils;
import com.example.exampleplugin.npc.BattleheartCameraService;
import com.example.exampleplugin.npc.CompanionCombatSettings;
import com.example.exampleplugin.npc.TrackedSummonStore;
import com.example.exampleplugin.levels.ui.MainMenuPage;
import com.example.exampleplugin.levels.model.LevelDefinition;
import com.example.exampleplugin.levels.model.TransformData;
import com.example.exampleplugin.levels.model.Vector3Data;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.protocol.packets.inventory.SetActiveSlot;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.server.core.entity.InteractionManager;
import com.hypixel.hytale.server.core.command.system.CommandManager;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.modules.interaction.InteractionModule;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.WorldConfig;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public final class LevelSessionManager {
    private static final String RUN_WORLD_PREFIX = "bh-level-run-";
    private static final LevelSessionManager INSTANCE = new LevelSessionManager();

    @Nullable
    private volatile ActiveRun activeRun;

    private LevelSessionManager() {
    }

    @Nonnull
    public static LevelSessionManager get() {
        return INSTANCE;
    }

    public synchronized boolean hasActiveRun() {
        return this.activeRun != null;
    }

    @Nullable
    public synchronized ActiveRunSnapshot getSnapshot() {
        return this.activeRun == null ? null : this.activeRun.snapshot();
    }

    @Nonnull
    public CompletableFuture<StartResult> startLevelForPlayer(@Nonnull PlayerRef starter, @Nonnull String levelId) {
        if (hasActiveRun()) {
            UUID starterUuid = starter.getUuid();
            return finishActiveRun(false).thenCompose(ignored -> {
                PlayerRef refreshedStarter = Universe.get().getPlayer(starterUuid);
                if (refreshedStarter == null) {
                    return CompletableFuture.failedFuture(new IllegalStateException("Player is unavailable after ending the current run."));
                }
                return startLevelForPlayer(refreshedStarter, levelId);
            });
        }
        CompanionCombatSettings.setCombatEnabled(true);
        LevelDefinition level = LevelConfigStore.get().getLevelById(levelId);
        if (level == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Unknown level id: " + levelId));
        }
        if (level.mapWorldName == null || level.mapWorldName.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Level '" + levelId + "' has no mapWorldName."));
        }

        final ActiveRun run;
        synchronized (this) {
            if (this.activeRun != null) {
                return CompletableFuture.failedFuture(new IllegalStateException("A level run is already active."));
            }

            String runWorldName = buildRunWorldName(level.levelId == null ? "level" : level.levelId);
            Path runWorldPath = Universe.get().getPath().resolve("worlds").resolve(runWorldName);
            List<UUID> participantPlayerUuids = selectParticipants(starter);
            this.activeRun = new ActiveRun(
                Objects.requireNonNullElse(level.levelId, "level_1"),
                Objects.requireNonNullElse(level.levelName, "Level"),
                level.mapWorldName,
                runWorldName,
                runWorldPath,
                starter.getUuid(),
                participantPlayerUuids,
                starter.getTransform().clone(),
                toTransform(level.playerSpawn),
                toTransform(level.fallbackReturnSpawn),
                false
            );
            run = this.activeRun;
        }

        Universe universe = Universe.get();
        World startingWorld = resolvePlayerWorld(starter, universe);
        Path templatePath = universe.getPath().resolve("worlds").resolve(run.templateWorldName);

        return CompletableFuture.runAsync(() -> copyDirectory(templatePath, run.runWorldPath))
            .thenCompose(ignored -> loadAndInstantiateRunWorld(universe, run))
            .thenCompose(runWorld -> {
                run.runWorldUuid = runWorld.getWorldConfig().getUuid();
                Transform targetSpawn = run.levelSpawnTransform != null ? run.levelSpawnTransform : starter.getTransform();
                boolean overviewCamera = Boolean.TRUE.equals(level.overviewCamera);
                return moveParticipantsToWorld(universe, run, runWorld, targetSpawn, overviewCamera)
                    .thenRun(() -> cleanupAbandonedRunWorld(universe, startingWorld, run))
                    .thenCompose(x -> configureRunWorldTime(universe, run).thenApply(y -> runWorld));
            })
            .thenApply(runWorld -> {
                return new StartResult(run.levelId, run.levelName, run.runWorldName, run.runWorldUuid);
            })
            .exceptionallyCompose(throwable -> cleanupFailedStart(run, throwable));
    }

    @Nonnull
    public CompletableFuture<StartResult> startEndlessLevelForPlayer(@Nonnull PlayerRef starter, @Nonnull String levelId) {
        if (hasActiveRun()) {
            UUID starterUuid = starter.getUuid();
            return finishActiveRun(false).thenCompose(ignored -> {
                PlayerRef refreshedStarter = Universe.get().getPlayer(starterUuid);
                if (refreshedStarter == null) {
                    return CompletableFuture.failedFuture(new IllegalStateException("Player is unavailable after ending the current run."));
                }
                return startEndlessLevelForPlayer(refreshedStarter, levelId);
            });
        }
        CompanionCombatSettings.setCombatEnabled(true);
        LevelDefinition level = LevelConfigStore.get().getLevelById(levelId);
        if (level == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Unknown level id: " + levelId));
        }
        if (level.mapWorldName == null || level.mapWorldName.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Level '" + levelId + "' has no mapWorldName."));
        }

        final ActiveRun run;
        synchronized (this) {
            if (this.activeRun != null) {
                return CompletableFuture.failedFuture(new IllegalStateException("A level run is already active."));
            }

            String runWorldName = buildRunWorldName(level.levelId == null ? "level" : level.levelId);
            Path runWorldPath = Universe.get().getPath().resolve("worlds").resolve(runWorldName);
            List<UUID> participantPlayerUuids = selectParticipants(starter);
            this.activeRun = new ActiveRun(
                Objects.requireNonNullElse(level.levelId, "level_1"),
                Objects.requireNonNullElse(level.levelName, "Level"),
                level.mapWorldName,
                runWorldName,
                runWorldPath,
                starter.getUuid(),
                participantPlayerUuids,
                starter.getTransform().clone(),
                toTransform(level.playerSpawn),
                toTransform(level.fallbackReturnSpawn),
                true
            );
            run = this.activeRun;
        }

        Universe universe = Universe.get();
        World startingWorld = resolvePlayerWorld(starter, universe);
        Path templatePath = universe.getPath().resolve("worlds").resolve(run.templateWorldName);

        return CompletableFuture.runAsync(() -> copyDirectory(templatePath, run.runWorldPath))
            .thenCompose(ignored -> loadAndInstantiateRunWorld(universe, run))
            .thenCompose(runWorld -> {
                run.runWorldUuid = runWorld.getWorldConfig().getUuid();
                Transform targetSpawn = run.levelSpawnTransform != null ? run.levelSpawnTransform : starter.getTransform();
                boolean overviewCamera = Boolean.TRUE.equals(level.overviewCamera);
                return moveParticipantsToWorld(universe, run, runWorld, targetSpawn, overviewCamera)
                    .thenRun(() -> cleanupAbandonedRunWorld(universe, startingWorld, run))
                    .thenApply(x -> runWorld);
            })
            .thenApply(runWorld -> {
                return new StartResult(run.levelId, run.levelName, run.runWorldName, run.runWorldUuid);
            })
            .exceptionallyCompose(throwable -> cleanupFailedStart(run, throwable));
    }

    @Nonnull
    public CompletableFuture<StartResult> startAiEndlessRunForPlayer(@Nonnull PlayerRef starter, @Nonnull World generatedWorld, @Nonnull Transform spawnTransform) {
        if (hasActiveRun()) {
            UUID starterUuid = starter.getUuid();
            return finishActiveRun(false).thenCompose(ignored -> {
                PlayerRef refreshedStarter = Universe.get().getPlayer(starterUuid);
                if (refreshedStarter == null) {
                    return CompletableFuture.failedFuture(new IllegalStateException("Player is unavailable after ending the current run."));
                }
                return startAiEndlessRunForPlayer(refreshedStarter, generatedWorld, spawnTransform);
            });
        }
        CompanionCombatSettings.setCombatEnabled(true);
        
        final ActiveRun run;
        synchronized (this) {
            if (this.activeRun != null) {
                return CompletableFuture.failedFuture(new IllegalStateException("A level run is already active."));
            }

            List<UUID> participantPlayerUuids = selectParticipants(starter);
            this.activeRun = new ActiveRun(
                "ai_endless",
                "Endless AI World",
                generatedWorld.getName(),
                generatedWorld.getName(),
                Universe.get().getPath().resolve("worlds").resolve(generatedWorld.getName()),
                starter.getUuid(),
                participantPlayerUuids,
                starter.getTransform().clone(),
                spawnTransform,
                starter.getTransform().clone(),
                true
            );
            run = this.activeRun;
        }

        Universe universe = Universe.get();
        run.runWorldUuid = generatedWorld.getWorldConfig().getUuid();
        
        return moveParticipantsToWorld(universe, run, generatedWorld, spawnTransform, false).thenApply(x -> {
            return new StartResult(run.levelId, run.levelName, run.runWorldName, run.runWorldUuid);
        }).exceptionallyCompose(throwable -> cleanupFailedStart(run, throwable));
    }

    @Nonnull
    public CompletableFuture<EndResult> finishActiveRun(boolean victory) {
        CompanionCombatSettings.setCombatEnabled(false);
        final ActiveRun run;
        synchronized (this) {
            if (this.activeRun == null) {
                return CompletableFuture.completedFuture(new EndResult(false, null, "No active level run."));
            }
            run = this.activeRun;
        }

        Universe universe = Universe.get();
        World runWorld = universe.getWorld(run.runWorldName);
        World returnWorld = universe.getDefaultWorld();
        if (returnWorld == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Default world is unavailable."));
        }

        Transform returnSpawn = run.returnSpawnTransform != null ? run.returnSpawnTransform : run.starterOriginalTransform;

        CompletableFuture<Void> transferFuture;
        if (runWorld != null && runWorld.isAlive()) {
            clearRunWorldUiBeforeTransfer(runWorld);
            transferFuture = movePlayersFromWorld(runWorld, returnWorld, returnSpawn);
        } else {
            transferFuture = CompletableFuture.completedFuture(null);
        }

        return transferFuture.thenApply(ignored -> {
            if (run.runWorldUuid != null) {
                LevelRunDirectorSystem.clearVictoryOverlayActive(run.runWorldUuid);
            }
            if (universe.getWorld(run.runWorldName) != null) {
                universe.removeWorld(run.runWorldName);
            }
            if (run.runWorldUuid != null) {
                LevelRunCombatStore.get().clearWorld(run.runWorldUuid);
            }
            deleteDirectoryIfPresent(run.runWorldPath);
            synchronized (this) {
                if (this.activeRun == run) {
                    this.activeRun = null;
                }
            }
            CompanionCombatSettings.setCombatEnabled(true);
            String msg = victory ? "Victory! Run cleaned up." : "Defeat. Returned to default world and cleaned up.";
            return new EndResult(true, run.runWorldName, msg);
        }).exceptionally(ex -> {
            synchronized (this) {
                if (this.activeRun == run) {
                    this.activeRun = null;
                }
            }
            CompanionCombatSettings.setCombatEnabled(true);
            return new EndResult(false, run.runWorldName, "Failed to finish run: " + ex.getMessage());
        });
    }

    @Nonnull
    public CompletableFuture<EndResult> finishDefeatedRunInPlace(@Nonnull UUID defeatedPlayerUuid) {
        CompanionCombatSettings.setCombatEnabled(false);
        final ActiveRun run;
        synchronized (this) {
            if (this.activeRun == null) {
                return CompletableFuture.completedFuture(new EndResult(false, null, "No active level run."));
            }
            run = this.activeRun;
            this.activeRun = null;
        }

        Universe universe = Universe.get();
        World runWorld = universe.getWorld(run.runWorldName);
        if (runWorld == null || !runWorld.isAlive()) {
            if (run.runWorldUuid != null) {
                LevelRunCombatStore.get().clearWorld(run.runWorldUuid);
                LevelRunDirectorSystem.clearVictoryOverlayActive(run.runWorldUuid);
            }
            CompanionCombatSettings.setCombatEnabled(true);
            return CompletableFuture.completedFuture(new EndResult(true, run.runWorldName, "Defeat. Run cleaned up."));
        }

        return CompletableFuture.runAsync(() -> {
            PlayerRef playerRef = universe.getPlayer(defeatedPlayerUuid);
            removeRunEntities(run.runWorldUuid, playerRef);
            if (playerRef != null && run.runWorldUuid != null && run.runWorldUuid.equals(playerRef.getWorldUuid())) {
                restoreHealth(playerRef);
                clearDeathComponent(playerRef);
                clearEffects(playerRef);
                clearInteractions(playerRef);
                clearRunStateBeforeMenuTransfer(playerRef);
                openMainMenuInCurrentWorld(playerRef);
            }
            if (run.runWorldUuid != null) {
                LevelRunCombatStore.get().clearWorld(run.runWorldUuid);
                LevelRunDirectorSystem.clearVictoryOverlayActive(run.runWorldUuid);
            }
        }, runWorld).handle((ignored, throwable) -> {
            CompanionCombatSettings.setCombatEnabled(true);
            if (throwable != null) {
                Throwable cause = throwable instanceof CompletionException && throwable.getCause() != null ? throwable.getCause() : throwable;
                return new EndResult(false, run.runWorldName, "Failed to finish run: " + cause.getMessage());
            }
            return new EndResult(true, run.runWorldName, "Defeat. Returned to main menu.");
        });
    }

    public synchronized boolean isActiveRunWorld(@Nullable UUID worldUuid) {
        return worldUuid != null && this.activeRun != null && worldUuid.equals(this.activeRun.runWorldUuid);
    }

    public synchronized boolean isRunOwner(@Nonnull UUID playerUuid) {
        return this.activeRun != null && this.activeRun.ownerPlayerUuid.equals(playerUuid);
    }

    public synchronized boolean isRunParticipant(@Nonnull UUID playerUuid) {
        return this.activeRun != null && this.activeRun.participantPlayerUuids.contains(playerUuid);
    }

    public synchronized int getCompanionSpawnCount() {
        if (this.activeRun == null) {
            return 3;
        }
        return Math.max(0, 3 - this.activeRun.participantPlayerUuids.size());
    }

    @Nonnull
    private CompletableFuture<World> loadAndInstantiateRunWorld(@Nonnull Universe universe, @Nonnull ActiveRun run) {
        Path configPath = run.runWorldPath.resolve("config.bson");
        if (!Files.exists(configPath)) {
            configPath = run.runWorldPath.resolve("config.json");
        }
        Path finalConfigPath = configPath;
        return WorldConfig.load(finalConfigPath).thenCompose(config -> {
            config.setUuid(UUID.randomUUID());
            config.setDisplayName("Run " + run.levelName);
            config.setDeleteOnRemove(true);
            config.markChanged();
            return universe.makeWorld(run.runWorldName, run.runWorldPath, config);
        });
    }

    @Nonnull
    private CompletableFuture<StartResult> cleanupFailedStart(@Nonnull ActiveRun run, @Nonnull Throwable throwable) {
        try {
            Universe universe = Universe.get();
            if (universe.getWorld(run.runWorldName) != null) {
                universe.removeWorld(run.runWorldName);
            }
            if (run.runWorldUuid != null) {
                LevelRunCombatStore.get().clearWorld(run.runWorldUuid);
            }
            deleteDirectoryIfPresent(run.runWorldPath);
        } catch (Exception ignored) {
        }

        synchronized (this) {
            if (this.activeRun == run) {
                this.activeRun = null;
            }
        }
        CompanionCombatSettings.setCombatEnabled(true);
        Throwable cause = throwable instanceof CompletionException && throwable.getCause() != null ? throwable.getCause() : throwable;
        return CompletableFuture.failedFuture(cause);
    }

    private static void cleanupAbandonedRunWorld(
        @Nonnull Universe universe,
        @Nullable World previousWorld,
        @Nonnull ActiveRun currentRun
    ) {
        if (previousWorld == null || previousWorld.getName() == null) {
            return;
        }
        String previousWorldName = previousWorld.getName();
        if (!previousWorldName.startsWith(RUN_WORLD_PREFIX) || previousWorldName.equals(currentRun.runWorldName)) {
            return;
        }
        UUID previousWorldId = previousWorld.getWorldConfig().getUuid();
        for (PlayerRef playerRef : universe.getPlayers()) {
            UUID playerWorldId = playerRef.getWorldUuid();
            if (playerWorldId != null && playerWorldId.equals(previousWorldId)) {
                return;
            }
        }
        if (universe.getWorld(previousWorldName) != null) {
            universe.removeWorld(previousWorldName);
        }
        LevelRunCombatStore.get().clearWorld(previousWorldId);
        deleteDirectoryIfPresent(universe.getPath().resolve("worlds").resolve(previousWorldName));
    }

    @Nonnull
    private static CompletableFuture<Void> movePlayersFromWorld(
        @Nonnull World fromWorld,
        @Nonnull World toWorld,
        @Nullable Transform targetSpawn
    ) {
        UUID fromWorldId = fromWorld.getWorldConfig().getUuid();
        List<CompletableFuture<Void>> transfers = new ArrayList<>();
        for (PlayerRef playerRef : Universe.get().getPlayers()) {
            UUID playerWorldId = playerRef.getWorldUuid();
            if (playerWorldId != null && playerWorldId.equals(fromWorldId)) {
                transfers.add(movePlayerToWorld(playerRef, fromWorld, toWorld, targetSpawn, false, true));
            }
        }
        if (transfers.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.allOf(transfers.toArray(new CompletableFuture[0]));
    }

    private static void clearRunWorldUiBeforeTransfer(@Nonnull World runWorld) {
        UUID runWorldId = runWorld.getWorldConfig().getUuid();
        for (PlayerRef playerRef : Universe.get().getPlayers()) {
            UUID playerWorldId = playerRef.getWorldUuid();
            if (playerWorldId == null || !playerWorldId.equals(runWorldId)) {
                continue;
            }

            Ref<EntityStore> ref = playerRef.getReference();
            if (ref == null || !ref.isValid()) {
                continue;
            }
            Store<EntityStore> store = ref.getStore();
            Player player = store.getComponent(ref, Player.getComponentType());
            if (player != null) {
                player.getHudManager().resetHud(playerRef);
                player.getPageManager().setPage(ref, store, Page.None);
            }
        }
        LevelRunDirectorSystem.clearVictoryOverlayActive(runWorldId);
    }

    @Nonnull
    private static CompletableFuture<Void> moveParticipantsToWorld(
        @Nonnull Universe universe,
        @Nonnull ActiveRun run,
        @Nonnull World runWorld,
        @Nullable Transform targetSpawn,
        boolean overviewCamera
    ) {
        List<CompletableFuture<Void>> transfers = new ArrayList<>();
        for (UUID participantUuid : run.participantPlayerUuids) {
            PlayerRef playerRef = universe.getPlayer(participantUuid);
            if (playerRef == null) {
                continue;
            }
            World fromWorld = resolvePlayerWorld(playerRef, universe);
            transfers.add(movePlayerToWorld(playerRef, fromWorld, runWorld, targetSpawn, overviewCamera, false));
        }
        if (transfers.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.allOf(transfers.toArray(new CompletableFuture[0]));
    }

    @Nonnull
    private static CompletableFuture<Void> configureRunWorldTime(
        @Nonnull Universe universe,
        @Nonnull ActiveRun run
    ) {
        PlayerRef owner = universe.getPlayer(run.ownerPlayerUuid);
        if (owner == null || run.runWorldUuid == null || owner.getWorldUuid() == null || !owner.getWorldUuid().equals(run.runWorldUuid)) {
            return CompletableFuture.completedFuture(null);
        }
        return CommandManager.get().handleCommand(owner, "time set 12")
            .thenCompose(ignored -> CommandManager.get().handleCommand(owner, "time pause"))
            .thenApply(ignored -> null);
    }

    @Nonnull
    private static CompletableFuture<Void> movePlayerToWorld(
        @Nonnull PlayerRef playerRef,
        @Nonnull World fromWorld,
        @Nonnull World toWorld,
        @Nullable Transform targetSpawn,
        boolean overviewCamera,
        boolean clearRunState
    ) {
        return CompletableFuture.runAsync(() -> {
                // Ensure player is in a clean state before leaving the current world
                // This prevents "dead" state from carrying over and causing crashes in the new world
                restoreHealth(playerRef);
                clearDeathComponent(playerRef);
                clearEffects(playerRef);
                if (clearRunState) {
                    clearRunStateBeforeMenuTransfer(playerRef);
                }
                clearInteractions(playerRef);
                playerRef.removeFromStore();
            }, fromWorld)
            .thenCompose(ignored -> {
                CompletableFuture<PlayerRef> addFuture = toWorld.addPlayer(
                    playerRef,
                    targetSpawn == null ? null : targetSpawn.clone(),
                    Boolean.TRUE,
                    Boolean.FALSE
                );
                if (addFuture == null) {
                    return CompletableFuture.failedFuture(new IllegalStateException("Player add returned null."));
                }
                return addFuture.thenApply(added -> {
                    restoreHealth(added);
                    clearDeathComponent(added);
                    clearEffects(added);
                    if (clearRunState) {
                        return null;
                    }
                    if (overviewCamera) {
                        BattleheartCameraService.applyArenaCamera(added);
                    } else {
                        BattleheartCameraService.applyThirdPersonCamera(added);
                    }
                    applyRunPlayerState(added);
                    return null;
                });
            });
    }

    private static void applyRunPlayerState(@Nonnull PlayerRef playerRef) {
        CommandManager commandManager = CommandManager.get();
        commandManager.handleCommand(playerRef, "gamemode adventure");
        commandManager.handleCommand(playerRef, "fullbright");
        commandManager.handleCommand(playerRef, "effect give @s night_vision 999999 1 true");
    }

    private static void openMainMenuInCurrentWorld(@Nonnull PlayerRef playerRef) {
        Ref<EntityStore> ref = playerRef.getReference();
        if (ref == null || !ref.isValid()) {
            return;
        }
        Store<EntityStore> store = ref.getStore();
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            return;
        }
        player.getPageManager().openCustomPage(ref, store, new MainMenuPage(playerRef));

        CommandManager commandManager = CommandManager.get();
        commandManager.handleCommand(playerRef, "hud hide ultimate");
        commandManager.handleCommand(playerRef, "gamemode creative");
        commandManager.handleCommand(playerRef, "fly false");
        commandManager.handleCommand(playerRef, "effect give @s night_vision 999999 1 true");
    }

    private static void removeRunEntities(
        @Nullable UUID runWorldUuid,
        @Nullable PlayerRef playerRef
    ) {
        Ref<EntityStore> playerEntityRef = playerRef == null ? null : playerRef.getReference();
        if (playerEntityRef != null && playerEntityRef.isValid()) {
            Store<EntityStore> store = playerEntityRef.getStore();
            for (Ref<EntityStore> npcRef : TrackedSummonStore.getTrackedSnapshot(playerEntityRef)) {
                removeEntityIfLocal(store, npcRef);
            }
        }

        if (runWorldUuid == null) {
            return;
        }
        LevelRunCombatStore.CombatWorldState combatState = LevelRunCombatStore.get().getWorld(runWorldUuid);
        if (combatState == null) {
            return;
        }
        for (Ref<EntityStore> enemyRef : combatState.activeEnemies()) {
            removeEntityIfValid(enemyRef);
        }
    }

    private static void removeEntityIfLocal(@Nonnull Store<EntityStore> store, @Nullable Ref<EntityStore> ref) {
        if (ref != null && ref.isValid() && ref.getStore() == store) {
            store.removeEntity(ref, RemoveReason.REMOVE);
        }
    }

    private static void removeEntityIfValid(@Nullable Ref<EntityStore> ref) {
        if (ref != null && ref.isValid()) {
            ref.getStore().removeEntity(ref, RemoveReason.REMOVE);
        }
    }

    private static void clearRunStateBeforeMenuTransfer(@Nonnull PlayerRef playerRef) {
        Ref<EntityStore> ref = playerRef.getReference();
        if (ref == null || !ref.isValid()) {
            return;
        }

        Store<EntityStore> store = ref.getStore();
        TrackedSummonStore.clearTracking(ref);

        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            return;
        }
        player.getHudManager().resetHud(playerRef);
        player.getPageManager().setPage(ref, store, Page.None);

        if (player.getInventory() == null) {
            return;
        }

        Inventory inventory = player.getInventory();
        clearContainer(inventory.getHotbar());
        clearContainer(inventory.getStorage());
        clearContainer(inventory.getArmor());
        clearContainer(inventory.getBackpack());
        clearContainer(inventory.getTools());
        clearContainer(inventory.getUtility());
        inventory.setActiveHotbarSlot(ref, (byte) 0, store);
        playerRef.getPacketHandler().writeNoCache(new SetActiveSlot(Inventory.HOTBAR_SECTION_ID, 0));
    }

    private static void clearContainer(@Nullable ItemContainer container) {
        if (container != null) {
            container.clear();
        }
    }

    private static void restoreHealth(@Nonnull PlayerRef playerRef) {
        Ref<EntityStore> ref = playerRef.getReference();
        if (ref == null || !ref.isValid()) {
            return;
        }
        restoreHealth(ref);
    }

    private static void restoreHealth(@Nonnull Ref<EntityStore> ref) {
        Store<EntityStore> store = ref.getStore();
        EntityStatMap stats = store.getComponent(ref, EntityStatMap.getComponentType());
        if (stats == null) {
            return;
        }
        stats.maximizeStatValue(DefaultEntityStatTypes.getHealth());
        store.putComponent(ref, EntityStatMap.getComponentType(), stats);
    }

    private static void clearDeathComponent(@Nonnull PlayerRef playerRef) {
        Ref<EntityStore> ref = playerRef.getReference();
        if (ref == null || !ref.isValid()) {
            return;
        }
        clearDeathComponent(ref);
    }

    private static void clearDeathComponent(@Nonnull Ref<EntityStore> ref) {
        Store<EntityStore> store = ref.getStore();
        if (store.getComponent(ref, DeathComponent.getComponentType()) != null) {
            store.removeComponent(ref, DeathComponent.getComponentType());
        }
    }

    private static void clearEffects(@Nonnull PlayerRef playerRef) {
        Ref<EntityStore> ref = playerRef.getReference();
        if (ref == null || !ref.isValid()) {
            return;
        }
        clearEffects(ref);
    }

    private static void clearEffects(@Nonnull Ref<EntityStore> ref) {
        Store<EntityStore> store = ref.getStore();
        EffectControllerComponent effects = store.getComponent(ref, EffectControllerComponent.getComponentType());
        if (effects == null) {
            return;
        }
        effects.clearEffects(ref, store);
        store.putComponent(ref, EffectControllerComponent.getComponentType(), effects);
    }

    private static void clearInteractions(@Nonnull PlayerRef playerRef) {
        Ref<EntityStore> ref = playerRef.getReference();
        if (ref == null || !ref.isValid()) {
            return;
        }
        Store<EntityStore> store = ref.getStore();
        InteractionManager interactionManager = store.getComponent(ref, InteractionModule.get().getInteractionManagerComponent());
        if (interactionManager != null) {
            interactionManager.clear();
        }
    }

    @Nonnull
    private static World resolvePlayerWorld(@Nonnull PlayerRef playerRef, @Nonnull Universe universe) {
        UUID current = playerRef.getWorldUuid();
        if (current != null) {
            World world = universe.getWorld(current);
            if (world != null) {
                return world;
            }
        }
        World fallback = universe.getDefaultWorld();
        if (fallback == null) {
            throw new IllegalStateException("Default world is unavailable.");
        }
        return fallback;
    }

    @Nullable
    private static Transform toTransform(@Nullable TransformData data) {
        if (data == null || data.position == null) {
            return null;
        }
        Vector3Data pos = data.position;
        return new Transform(pos.x, pos.y, pos.z, data.yaw, data.pitch, data.roll);
    }

    private static void copyDirectory(@Nonnull Path source, @Nonnull Path target) {
        if (!Files.isDirectory(source)) {
            throw new IllegalArgumentException("Template world path does not exist: " + source);
        }
        try {
            if (Files.exists(target)) {
                deleteDirectoryIfPresent(target);
            }
            try (var stream = Files.walk(source)) {
                stream.forEach(path -> {
                    Path relative = source.relativize(path);
                    Path destination = target.resolve(relative.toString());
                    try {
                        if (Files.isDirectory(path)) {
                            Files.createDirectories(destination);
                        } else if (Files.isRegularFile(path)) {
                            Path parent = destination.getParent();
                            if (parent != null) {
                                Files.createDirectories(parent);
                            }
                            Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                        }
                    } catch (IOException e) {
                        throw new CompletionException(e);
                    }
                });
            }
        } catch (IOException e) {
            throw new CompletionException(e);
        }
    }

    private static void deleteDirectoryIfPresent(@Nonnull Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new CompletionException(e);
                }
            });
        } catch (IOException e) {
            throw new CompletionException(e);
        }
    }

    @Nonnull
    private static String buildRunWorldName(@Nonnull String levelId) {
        String safe = levelId.toLowerCase().replaceAll("[^a-z0-9_-]", "-");
        return RUN_WORLD_PREFIX + safe + "-" + System.currentTimeMillis();
    }

    @Nonnull
    private static List<UUID> selectParticipants(@Nonnull PlayerRef starter) {
        Set<UUID> selected = new LinkedHashSet<>();
        selected.add(starter.getUuid());
        for (PlayerRef playerRef : Universe.get().getPlayers()) {
            if (playerRef == null || playerRef.getUuid().equals(starter.getUuid())) {
                continue;
            }
            selected.add(playerRef.getUuid());
            if (selected.size() >= 3) {
                break;
            }
        }
        return List.copyOf(selected);
    }

    private static final class ActiveRun {
        @Nonnull
        private final String levelId;
        @Nonnull
        private final String levelName;
        @Nonnull
        private final String templateWorldName;
        @Nonnull
        private final String runWorldName;
        @Nonnull
        private final Path runWorldPath;
        @Nonnull
        private final UUID ownerPlayerUuid;
        @Nonnull
        private final List<UUID> participantPlayerUuids;
        @Nonnull
        private final Transform starterOriginalTransform;
        @Nullable
        private final Transform levelSpawnTransform;
        @Nullable
        private final Transform returnSpawnTransform;
        @Nullable
        private UUID runWorldUuid;
        private final boolean isEndless;

        private ActiveRun(
            @Nonnull String levelId,
            @Nonnull String levelName,
            @Nonnull String templateWorldName,
            @Nonnull String runWorldName,
            @Nonnull Path runWorldPath,
            @Nonnull UUID ownerPlayerUuid,
            @Nonnull List<UUID> participantPlayerUuids,
            @Nonnull Transform starterOriginalTransform,
            @Nullable Transform levelSpawnTransform,
            @Nullable Transform returnSpawnTransform,
            boolean isEndless
        ) {
            this.levelId = levelId;
            this.levelName = levelName;
            this.templateWorldName = templateWorldName;
            this.runWorldName = runWorldName;
            this.runWorldPath = runWorldPath;
            this.ownerPlayerUuid = ownerPlayerUuid;
            this.participantPlayerUuids = List.copyOf(participantPlayerUuids);
            this.starterOriginalTransform = starterOriginalTransform;
            this.levelSpawnTransform = levelSpawnTransform;
            this.returnSpawnTransform = returnSpawnTransform;
            this.isEndless = isEndless;
        }

        @Nonnull
        private ActiveRunSnapshot snapshot() {
            return new ActiveRunSnapshot(
                this.levelId,
                this.levelName,
                this.templateWorldName,
                this.runWorldName,
                this.ownerPlayerUuid,
                this.participantPlayerUuids,
                this.runWorldUuid,
                this.isEndless
            );
        }
    }

    public record StartResult(
        @Nonnull String levelId,
        @Nonnull String levelName,
        @Nonnull String runWorldName,
        @Nonnull UUID runWorldUuid
    ) {
    }

    public record EndResult(
        boolean success,
        @Nullable String runWorldName,
        @Nonnull String message
    ) {
    }

    public record ActiveRunSnapshot(
        @Nonnull String levelId,
        @Nonnull String levelName,
        @Nonnull String templateWorldName,
        @Nonnull String runWorldName,
        @Nonnull UUID ownerPlayerUuid,
        @Nonnull List<UUID> participantPlayerUuids,
        @Nullable UUID runWorldUuid,
        boolean isEndless
    ) {
    }
}
