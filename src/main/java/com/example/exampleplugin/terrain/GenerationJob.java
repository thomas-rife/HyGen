package com.example.exampleplugin.terrain;

import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.file.Path;

public final class GenerationJob {
    private final PlayerRef playerRef;
    private final String prompt;
    private final String worldName;
    private final Path worldPath;
    private volatile GenerationJobState state;
    private volatile String statusMessage;
    private volatile World generatedWorld;
    private volatile TerrainPackageDescriptor packageDescriptor;
    private volatile TerrainPackage terrainPackage;

    public GenerationJob(
        @Nonnull PlayerRef playerRef,
        @Nonnull String prompt,
        @Nonnull String worldName,
        @Nonnull Path worldPath
    ) {
        this.playerRef = playerRef;
        this.prompt = prompt;
        this.worldName = worldName;
        this.worldPath = worldPath;
        this.state = GenerationJobState.CREATING_WORLD;
        this.statusMessage = GenerationJobState.CREATING_WORLD.name();
    }

    @Nonnull
    public PlayerRef playerRef() {
        return playerRef;
    }

    @Nonnull
    public String prompt() {
        return prompt;
    }

    @Nonnull
    public String worldName() {
        return worldName;
    }

    @Nonnull
    public Path worldPath() {
        return worldPath;
    }

    @Nonnull
    public GenerationJobState state() {
        return state;
    }

    @Nonnull
    public String statusMessage() {
        return statusMessage;
    }

    public void transition(@Nonnull GenerationJobState nextState, @Nonnull String message) {
        this.state = nextState;
        this.statusMessage = message;
    }

    @Nullable
    public World generatedWorld() {
        return generatedWorld;
    }

    public void setGeneratedWorld(@Nullable World generatedWorld) {
        this.generatedWorld = generatedWorld;
    }

    @Nullable
    public TerrainPackageDescriptor packageDescriptor() {
        return packageDescriptor;
    }

    public void setPackageDescriptor(@Nullable TerrainPackageDescriptor packageDescriptor) {
        this.packageDescriptor = packageDescriptor;
    }

    @Nullable
    public TerrainPackage terrainPackage() {
        return terrainPackage;
    }

    public void setTerrainPackage(@Nullable TerrainPackage terrainPackage) {
        this.terrainPackage = terrainPackage;
    }
}
