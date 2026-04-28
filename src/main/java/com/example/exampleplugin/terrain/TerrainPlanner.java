package com.example.exampleplugin.terrain;

import javax.annotation.Nonnull;

public final class TerrainPlanner {
    @Nonnull
    public TerrainRequestPlan planRequest(@Nonnull String prompt, int playerBlockX, int playerBlockZ, int seed, @Nonnull AiTerrainConfig config) {
        return planRequest(prompt, playerBlockX, playerBlockZ, seed, config, config.img2imgStrength);
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
        TerrainGenerationRequest request = new TerrainGenerationRequest(
            prompt,
            seed,
            null,
            null,
            AiTerrainSettings.DEFAULT_BASE_Y,
            AiTerrainSettings.DEFAULT_SCALE,
            AiTerrainSettings.DEFAULT_ARENA_SIZE,
            AiTerrainSettings.DEFAULT_ARENA_FEATHER,
            config.gridSize,
            config.overlap,
            img2imgStrength,
            playerBlockX,
            playerBlockZ
        );
        return new TerrainRequestPlan(request, playerBlockX, playerBlockZ);
    }
}
