package com.example.exampleplugin.terrain;

import com.hypixel.hytale.logger.HytaleLogger;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;

public final class AiTerrainService implements AutoCloseable {
    private static final AiTerrainService INSTANCE = new AiTerrainService();
    private static final HytaleLogger LOGGER = HytaleLogger.get("HyGen|AiTerrain");

    private final ExecutorService requestExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ai-terrain-request");
        thread.setDaemon(true);
        return thread;
    });

    private final TerrainPlanner planner = new TerrainPlanner();
    private final TerrainPackageLoader packageLoader = new TerrainPackageLoader();
    private final TerrainPlacer terrainPlacer = new TerrainPlacer();
    private final VoidWorldManager voidWorldManager = new VoidWorldManager();
    private final SafeSpawnFinder safeSpawnFinder = new SafeSpawnFinder();
    private final AiTerrainConfigStore configStore = AiTerrainConfigStore.get();
    private volatile TerrainPackageClient client;

    private AiTerrainService() {
    }

    @Nonnull
    public static AiTerrainService get() {
        return INSTANCE;
    }

    @Nonnull
    public TerrainRequestPlan planRequest(@Nonnull String prompt, int playerBlockX, int playerBlockZ, int seed, @Nonnull AiTerrainConfig config) {
        return planner.planRequest(prompt, playerBlockX, playerBlockZ, seed, config);
    }

    @Nonnull
    public TerrainRequestPlan planRequest(
        @Nonnull String prompt,
        int playerBlockX,
        int playerBlockZ,
        int seed,
        @Nonnull AiTerrainConfig config,
        float img2imgStrength
    ) {
        return planner.planRequest(prompt, playerBlockX, playerBlockZ, seed, config, img2imgStrength);
    }

    @Nonnull
    public TerrainPackageDescriptor requestTerrainPackageDescriptor(@Nonnull TerrainGenerationRequest request) {
        try {
            TerrainPackageClient currentClient = ensureClient();
            TerrainPackageDescriptor descriptor = currentClient.requestPackage(request);
            LOGGER.at(Level.INFO).log("Received terrain package descriptor for prompt '%s'.", request.prompt());
            return descriptor;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Failed to request terrain package.", e);
        } catch (IOException e) {
            throw new RuntimeException("Failed to request terrain package.", e);
        }
    }

    @Nonnull
    public TerrainPackage loadTerrainPackage(@Nonnull TerrainPackageDescriptor descriptor) {
        try {
            return packageLoader.load(ensureClient(), descriptor);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Failed to download terrain package.", e);
        } catch (IOException e) {
            throw new RuntimeException("Failed to download terrain package.", e);
        }
    }

    public void deleteTerrainPackage(@Nonnull TerrainPackageDescriptor descriptor) {
        try {
            ensureClient().deletePackage(descriptor);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Failed to delete terrain package.", e);
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete terrain package.", e);
        }
    }

    @Nonnull
    public TerrainPlacer terrainPlacer() {
        return terrainPlacer;
    }

    @Nonnull
    public VoidWorldManager voidWorldManager() {
        return voidWorldManager;
    }

    @Nonnull
    public SafeSpawnFinder safeSpawnFinder() {
        return safeSpawnFinder;
    }

    @Nonnull
    public AiTerrainConfig config() {
        return configStore.ensureLoaded();
    }

    @Nonnull
    public ExecutorService requestExecutor() {
        return requestExecutor;
    }

    @Override
    public synchronized void close() {
        TerrainPackageClient currentClient = this.client;
        if (currentClient != null) {
            currentClient.close();
            this.client = null;
        }
        this.requestExecutor.shutdownNow();
    }

    @Nonnull
    private synchronized TerrainPackageClient ensureClient() {
        if (this.client != null) {
            return this.client;
        }
        AiTerrainConfig config = configStore.ensureLoaded();
        this.client = new LocalHttpTerrainPackageClient(
            URI.create(config.pythonEndpoint),
            Duration.ofMillis(config.requestTimeoutMillis),
            Duration.ofMillis(config.downloadTimeoutMillis)
        );
        return this.client;
    }
}
