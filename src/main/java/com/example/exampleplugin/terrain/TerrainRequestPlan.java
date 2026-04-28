package com.example.exampleplugin.terrain;

import javax.annotation.Nonnull;

public record TerrainRequestPlan(
    @Nonnull TerrainGenerationRequest request,
    int playerAnchorX,
    int playerAnchorZ
) {
}
