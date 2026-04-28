package com.example.exampleplugin.terrain;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

public final class AiWorldGenCommand extends AbstractPlayerCommand {
    private final AiTerrainService service;

    public AiWorldGenCommand(@Nonnull AiTerrainService service) {
        super("aiworldgen", "Generate terrain in a new void world from an external terrain package service");
        this.service = service;
        this.setAllowsExtraArguments(true);
    }

    @Override
    protected void execute(
        @Nonnull CommandContext context,
        @Nonnull Store<EntityStore> store,
        @Nonnull Ref<EntityStore> ref,
        @Nonnull PlayerRef playerRef,
        @Nonnull World currentWorld
    ) {
        ParsedGenerationInput parsedInput;
        try {
            parsedInput = parseGenerationInput(context);
        } catch (IllegalArgumentException e) {
            context.sendMessage(Message.raw(e.getMessage()));
            return;
        }

        String prompt = parsedInput.prompt();
        if (prompt.isBlank()) {
            context.sendMessage(Message.raw("Usage: /aiworldgen <terrain prompt> [--seed <integer>] [--random] [--deterministic]"));
            return;
        }

        TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
        if (transform == null) {
            context.sendMessage(Message.raw("Could not resolve player position."));
            return;
        }

        int playerX = (int) Math.floor(transform.getPosition().getX());
        int playerZ = (int) Math.floor(transform.getPosition().getZ());
        int seed = parsedInput.seed();
        context.sendMessage(Message.raw("AI terrain seed: " + seed + " (" + parsedInput.seedMode() + ")"));
        AiTerrainConfig config = this.service.config();
        TerrainRequestPlan plan = this.service.planRequest(prompt, playerX, playerZ, seed, config);

        String localJobId = UUID.randomUUID().toString().replace("-", "");
        String worldName = this.service.voidWorldManager().buildWorldName(config, playerRef, localJobId);
        GenerationJob job = new GenerationJob(playerRef, prompt, worldName, this.service.voidWorldManager().worldPath(worldName));

        transition(job, currentWorld, "Creating generated void world " + worldName + "...");
        this.service.voidWorldManager().createVoidWorld(worldName, job.worldPath())
            .thenCompose(createdWorld -> {
                job.setGeneratedWorld(createdWorld);
                job.transition(GenerationJobState.REQUESTING_PYTHON, "Requesting Python terrain job...");
                sendPlayerMessage(currentWorld, playerRef, stateLine(job));
                return CompletableFuture.supplyAsync(() -> this.service.requestTerrainPackageDescriptor(plan.request()), this.service.requestExecutor());
            })
            .thenCompose(descriptor -> {
                job.setPackageDescriptor(descriptor);
                job.transition(GenerationJobState.DOWNLOADING_PACKAGE, "Downloading terrain package " + descriptor.jobId() + "...");
                sendPlayerMessage(currentWorld, playerRef, stateLine(job));
                return CompletableFuture.supplyAsync(() -> this.service.loadTerrainPackage(descriptor), this.service.requestExecutor());
            })
            .thenCompose(terrainPackage -> {
                job.setTerrainPackage(terrainPackage);
                if (terrainPackage.metadata().prompt == null || terrainPackage.metadata().prompt.isBlank()) {
                    terrainPackage.metadata().prompt = job.prompt();
                }
                job.transition(GenerationJobState.PLACING_TERRAIN, "Placing terrain in " + job.worldName() + "...");
                sendPlayerMessage(currentWorld, playerRef, stateLine(job));
                World generatedWorld = requireGeneratedWorld(job);
                int originX = terrainPackage.metadata().originX != 0
                    ? terrainPackage.metadata().originX
                    : plan.playerAnchorX() - (terrainPackage.width() / 2);
                int originZ = terrainPackage.metadata().originZ != 0
                    ? terrainPackage.metadata().originZ
                    : plan.playerAnchorZ() - (terrainPackage.depth() / 2);
                SafeSpawnFinder.SpawnSelection spawnSelection = this.service.safeSpawnFinder().findSpawn(terrainPackage, originX, originZ);
                return this.service.terrainPlacer().placeTerrainAsync(
                        generatedWorld,
                        terrainPackage,
                        originX,
                        originZ,
                        config.tileBatchSize,
                        AiTerrainPlacementMode.parse(config.placementMode),
                        config.surfaceShellDepth
                    )
                    .thenCompose(placed -> {
                        job.transition(GenerationJobState.PLACING_WATER, "Terrain placed. Filling water...");
                        sendPlayerMessage(currentWorld, playerRef, stateLine(job));
                        return this.service.terrainPlacer().placeWaterAsync(generatedWorld, terrainPackage, originX, originZ, config.tileBatchSize)
                            .thenCompose(waterPlaced -> {
                                job.transition(GenerationJobState.PLACING_DECORATIONS, "Water placed. Placing decorations...");
                                sendPlayerMessage(currentWorld, playerRef, stateLine(job));
                                return this.service.terrainPlacer().placeDecorationsAsync(generatedWorld, terrainPackage, originX, originZ, spawnSelection)
                                    .thenCompose(decorationsPlaced -> {
                                        job.transition(GenerationJobState.FINALIZING, "Finalizing world and spawn...");
                                        sendPlayerMessage(currentWorld, playerRef, stateLine(job));
                                        return this.service.terrainPlacer().finalizeSpawnAsync(generatedWorld, originX, originZ, terrainPackage, spawnSelection)
                                            .thenApply(ignored -> new CompletedGeneration(generatedWorld, spawnSelection.transform(), placed, waterPlaced, decorationsPlaced));
                                    });
                            });
                    });
            })
            .thenCompose(result -> cleanupPackage(job).handle((ignored, cleanupError) -> {
                if (cleanupError != null) {
                    LOGGER.at(Level.WARNING).log("Terrain package cleanup failed for world %s: %s", job.worldName(), rootMessage(cleanupError));
                }
                return result;
            }))
            .thenCompose(result -> {
                job.transition(GenerationJobState.COMPLETE, "Generation complete.");
                sendPlayerMessage(
                    currentWorld,
                    playerRef,
                    "AI terrain ready in "
                        + job.worldName()
                        + ". Terrain="
                        + result.terrainColumns()
                        + ", water="
                        + result.waterColumns()
                        + ", decorations="
                        + result.decorationsPlaced()
                );
                if (!config.teleportAfterComplete) {
                    return CompletableFuture.completedFuture(result);
                }
                World fromWorld = this.service.voidWorldManager().resolvePlayerWorld(playerRef);
                return this.service.voidWorldManager().teleportPlayer(playerRef, fromWorld, result.generatedWorld(), result.spawnTransform())
                    .thenApply(ignored -> result);
            })
            .thenAccept(result -> {
                if (config.teleportAfterComplete) {
                    sendPlayerMessage(result.generatedWorld(), playerRef, "Entered generated world " + job.worldName() + ".");
                }
            })
            .exceptionally(throwable -> {
                handleFailure(job, currentWorld, throwable, config.deleteFailedWorlds);
                return null;
            });
    }

    @Nonnull
    private CompletableFuture<Void> cleanupPackage(@Nonnull GenerationJob job) {
        TerrainPackageDescriptor descriptor = job.packageDescriptor();
        if (descriptor == null) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.runAsync(() -> this.service.deleteTerrainPackage(descriptor), this.service.requestExecutor());
    }

    private void handleFailure(@Nonnull GenerationJob job, @Nonnull World currentWorld, @Nonnull Throwable throwable, boolean deleteFailedWorld) {
        job.transition(GenerationJobState.FAILED, rootMessage(throwable));
        try {
            if (job.packageDescriptor() != null) {
                this.service.deleteTerrainPackage(job.packageDescriptor());
            }
        } catch (RuntimeException cleanupError) {
            LOGGER.at(Level.WARNING).log("Terrain package cleanup after failure also failed for %s: %s", job.worldName(), rootMessage(cleanupError));
        }
        if (deleteFailedWorld) {
            try {
                this.service.voidWorldManager().deleteWorldIfPresent(job.worldName(), job.worldPath());
            } catch (RuntimeException cleanupError) {
                LOGGER.at(Level.WARNING).log("Failed world cleanup failed for %s: %s", job.worldName(), rootMessage(cleanupError));
            }
        }
        sendPlayerMessage(currentWorld, job.playerRef(), "AI terrain generation failed: " + job.statusMessage());
    }

    private void transition(@Nonnull GenerationJob job, @Nonnull World notifyWorld, @Nonnull String message) {
        job.transition(GenerationJobState.CREATING_WORLD, message);
        sendPlayerMessage(notifyWorld, job.playerRef(), stateLine(job));
    }

    @Nonnull
    private static World requireGeneratedWorld(@Nonnull GenerationJob job) {
        World generatedWorld = job.generatedWorld();
        if (generatedWorld == null) {
            throw new IllegalStateException("Generated world was not created.");
        }
        return generatedWorld;
    }

    @Nonnull
    private static String stateLine(@Nonnull GenerationJob job) {
        return "[" + job.state().name() + "] " + job.statusMessage();
    }

    static void sendPlayerMessage(@Nonnull World world, @Nonnull PlayerRef playerRef, @Nonnull String message) {
        world.execute(() -> playerRef.sendMessage(Message.raw(message)));
    }

    @Nonnull
    private static String promptFromInput(@Nonnull CommandContext context) {
        String input = context.getInputString();
        if (input == null || input.isBlank()) {
            return "";
        }
        String[] split = input.trim().split("\\s+", 2);
        return split.length < 2 ? "" : split[1].trim();
    }

    @Nonnull
    private static ParsedGenerationInput parseGenerationInput(@Nonnull CommandContext context) {
        String input = context.getInputString();
        if (input == null || input.isBlank()) {
            return new ParsedGenerationInput("", ThreadLocalRandom.current().nextInt(), "random");
        }

        String[] split = input.trim().split("\\s+", 2);
        if (split.length < 2 || split[1].isBlank()) {
            return new ParsedGenerationInput("", ThreadLocalRandom.current().nextInt(), "random");
        }

        String raw = split[1].trim();
        String[] tokens = raw.split("\\s+");

        StringBuilder promptBuilder = new StringBuilder();
        Integer explicitSeed = null;
        boolean deterministic = false;
        boolean random = false;

        for (int i = 0; i < tokens.length; i++) {
            String token = tokens[i];

            if ("--seed".equalsIgnoreCase(token)) {
                if (i + 1 >= tokens.length) {
                    throw new IllegalArgumentException("--seed requires an integer value");
                }
                String seedText = tokens[++i];
                try {
                    explicitSeed = Integer.parseInt(seedText);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Invalid --seed value: " + seedText, e);
                }
                continue;
            }

            if (token.toLowerCase().startsWith("--seed=")) {
                String seedText = token.substring("--seed=".length());
                try {
                    explicitSeed = Integer.parseInt(seedText);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Invalid --seed value: " + seedText, e);
                }
                continue;
            }

            if ("--deterministic".equalsIgnoreCase(token) || "--repeatable".equalsIgnoreCase(token)) {
                deterministic = true;
                continue;
            }

            if ("--random".equalsIgnoreCase(token)) {
                random = true;
                continue;
            }

            if (promptBuilder.length() > 0) {
                promptBuilder.append(' ');
            }
            promptBuilder.append(token);
        }

        String prompt = promptBuilder.toString().trim();

        if (prompt.isBlank()) {
            return new ParsedGenerationInput("", ThreadLocalRandom.current().nextInt(), "random");
        }

        if (explicitSeed != null) {
            return new ParsedGenerationInput(prompt, explicitSeed, "explicit");
        }

        if (deterministic && !random) {
            int seed = AiTerrainSettings.SEED_SALT ^ prompt.hashCode();
            return new ParsedGenerationInput(prompt, seed, "deterministic");
        }

        int seed = ThreadLocalRandom.current().nextInt();
        return new ParsedGenerationInput(prompt, seed, "random");
    }

    @Nonnull
    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private record CompletedGeneration(
        @Nonnull World generatedWorld,
        @Nonnull Transform spawnTransform,
        int terrainColumns,
        int waterColumns,
        int decorationsPlaced
    ) {
    }

    private record ParsedGenerationInput(
        @Nonnull String prompt,
        int seed,
        @Nonnull String seedMode
    ) {
    }
}
