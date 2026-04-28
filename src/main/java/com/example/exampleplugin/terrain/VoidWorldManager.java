package com.example.exampleplugin.terrain;

import com.example.exampleplugin.npc.BattleheartCameraService;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.command.system.CommandManager;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.WorldConfig;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public final class VoidWorldManager {
    private static final HytaleLogger LOGGER = HytaleLogger.get("HyGen|AiTerrain");

    @Nonnull
    public String buildWorldName(@Nonnull AiTerrainConfig config, @Nonnull PlayerRef playerRef, @Nonnull String jobId) {
        String safePlayer = playerRef.getUuid().toString().substring(0, 8).toLowerCase();
        String shortJob = jobId.length() <= 8 ? jobId : jobId.substring(0, 8);
        return config.generatedWorldPrefix + safePlayer + "_" + System.currentTimeMillis() + "_" + shortJob;
    }

    @Nonnull
    public Path worldPath(@Nonnull String worldName) {
        return Universe.get().getPath().resolve("worlds").resolve(worldName);
    }

    @Nonnull
    public CompletableFuture<World> createVoidWorld(@Nonnull String worldName, @Nonnull Path worldPath) {
        Universe universe = Universe.get();
        World existing = universe.getWorld(worldName);
        if (existing != null) {
            return CompletableFuture.completedFuture(existing);
        }

        WorldConfig worldConfig = new WorldConfig();
        worldConfig.setUuid(UUID.randomUUID());
        worldConfig.setDisplayName(worldName);
        worldConfig.setDeleteOnRemove(true);
        worldConfig.setSavingConfig(true);
        worldConfig.setCanSaveChunks(true);
        worldConfig.setSaveNewChunks(true);
        worldConfig.setBlockTicking(false);
        worldConfig.setTicking(true);
        worldConfig.setWorldGenProvider(new AiVoidWorldGenProvider());

        return universe.makeWorld(worldName, worldPath, worldConfig);
    }

    public void deleteWorldIfPresent(@Nonnull String worldName, @Nonnull Path worldPath) {
        Universe universe = Universe.get();
        if (universe.getWorld(worldName) != null) {
            universe.removeWorld(worldName);
        }
        deleteDirectoryIfPresent(worldPath);
    }

    @Nonnull
    public CompletableFuture<Void> teleportPlayer(
        @Nonnull PlayerRef playerRef,
        @Nonnull World fromWorld,
        @Nonnull World toWorld,
        @Nonnull Transform targetSpawn
    ) {
        return CompletableFuture.runAsync(playerRef::removeFromStore, fromWorld)
            .thenCompose(ignored -> {
                CompletableFuture<PlayerRef> addFuture = toWorld.addPlayer(playerRef, targetSpawn.clone(), Boolean.TRUE, Boolean.FALSE);
                if (addFuture == null) {
                    return CompletableFuture.failedFuture(new IllegalStateException("Player add returned null."));
                }
                return addFuture.thenApply(added -> {
                    BattleheartCameraService.applyThirdPersonCamera(added);
                    CommandManager.get().handleCommand(added, "weather set Zone2_Sunny")
                        .exceptionally(throwable -> {
                            LOGGER.at(java.util.logging.Level.WARNING)
                                .log("Failed to apply Zone2_Sunny weather in generated world: %s", throwable.getMessage());
                            return null;
                        });
                    return null;
                });
            });
    }

    @Nonnull
    public World resolvePlayerWorld(@Nonnull PlayerRef playerRef) {
        Universe universe = Universe.get();
        UUID worldUuid = playerRef.getWorldUuid();
        if (worldUuid != null) {
            World current = universe.getWorld(worldUuid);
            if (current != null) {
                return current;
            }
        }
        World fallback = universe.getDefaultWorld();
        if (fallback == null) {
            throw new IllegalStateException("Default world is unavailable.");
        }
        return fallback;
    }

    private static void deleteDirectoryIfPresent(@Nonnull Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            });
        } catch (Exception e) {
            throw new CompletionException(e);
        }
    }
}
