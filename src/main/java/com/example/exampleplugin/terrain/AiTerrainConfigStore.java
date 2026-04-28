package com.example.exampleplugin.terrain;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.hypixel.hytale.server.core.universe.Universe;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

public final class AiTerrainConfigStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String CONFIG_FILE = "battleheart-ai-terrain.json";
    private static final AiTerrainConfigStore INSTANCE = new AiTerrainConfigStore();

    private volatile AiTerrainConfig config;

    private AiTerrainConfigStore() {
    }

    @Nonnull
    public static AiTerrainConfigStore get() {
        return INSTANCE;
    }

    @Nonnull
    public synchronized AiTerrainConfig ensureLoaded() {
        if (this.config != null) {
            return this.config;
        }

        Path configPath = Universe.get().getPath().resolve(CONFIG_FILE).normalize();
        try {
            if (!Files.exists(configPath)) {
                AiTerrainConfig defaults = sanitize(new AiTerrainConfig());
                writeConfig(configPath, defaults);
                this.config = defaults;
                return defaults;
            }

            try (Reader reader = Files.newBufferedReader(configPath)) {
                AiTerrainConfig loaded = GSON.fromJson(reader, AiTerrainConfig.class);
                this.config = sanitize(loaded == null ? new AiTerrainConfig() : loaded);
                writeConfig(configPath, this.config);
                return this.config;
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to load AI terrain config from " + configPath, e);
        }
    }

    @Nonnull
    private static AiTerrainConfig sanitize(@Nonnull AiTerrainConfig config) {
        if (config.pythonEndpoint == null || config.pythonEndpoint.isBlank()) {
            config.pythonEndpoint = AiTerrainSettings.DEFAULT_PYTHON_ENDPOINT;
        }
        if (config.generatedWorldPrefix == null || config.generatedWorldPrefix.isBlank()) {
            config.generatedWorldPrefix = AiTerrainSettings.DEFAULT_GENERATED_WORLD_PREFIX;
        }
        if (config.requestTimeoutMillis < AiTerrainSettings.REQUEST_TIMEOUT_MILLIS) {
            config.requestTimeoutMillis = AiTerrainSettings.REQUEST_TIMEOUT_MILLIS;
        }
        if (config.downloadTimeoutMillis <= 0) {
            config.downloadTimeoutMillis = AiTerrainSettings.DOWNLOAD_TIMEOUT_MILLIS;
        }
        if (config.tileBatchSize <= 0) {
            config.tileBatchSize = AiTerrainSettings.PLACEMENT_TILE_SIZE;
        }
        if (config.gridSize <= 0) {
            config.gridSize = AiTerrainSettings.DEFAULT_GRID_SIZE;
        }
        if (config.overlap < 0) {
            config.overlap = AiTerrainSettings.DEFAULT_OVERLAP;
        }
        if (Float.isNaN(config.img2imgStrength) || Float.isInfinite(config.img2imgStrength)) {
            config.img2imgStrength = AiTerrainSettings.DEFAULT_IMG2IMG_STRENGTH;
        } else {
            config.img2imgStrength = Math.max(0.0f, Math.min(1.0f, config.img2imgStrength));
        }
        config.placementMode = AiTerrainPlacementMode.parse(config.placementMode).name();
        if (config.surfaceShellDepth <= 0) {
            config.surfaceShellDepth = 8;
        }
        return config;
    }

    private static void writeConfig(@Nonnull Path path, @Nonnull AiTerrainConfig config) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (Writer writer = Files.newBufferedWriter(path)) {
            GSON.toJson(config, writer);
        }
    }
}
