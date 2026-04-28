package com.example.exampleplugin.terrain;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public record TerrainGenerationRequest(
    @Nonnull String prompt,
    int seed,
    @Nullable Integer width,
    @Nullable Integer depth,
    int baseY,
    int scale,
    int arenaSize,
    int arenaFeather,
    int gridSize,
    int overlap,
    float img2imgStrength,
    int placementOriginX,
    int placementOriginZ
) {
    public TerrainGenerationRequest {
        if (prompt.isBlank()) {
            throw new IllegalArgumentException("prompt must not be blank");
        }
        if (width != null && width <= 0) {
            throw new IllegalArgumentException("width must be > 0 when provided");
        }
        if (depth != null && depth <= 0) {
            throw new IllegalArgumentException("depth must be > 0 when provided");
        }
        if (scale <= 0) {
            throw new IllegalArgumentException("scale must be > 0");
        }
        if (arenaSize < 0 || arenaFeather < 0) {
            throw new IllegalArgumentException("arena settings must be >= 0");
        }
        if (gridSize <= 0) {
            throw new IllegalArgumentException("gridSize must be > 0");
        }
        if (overlap < 0) {
            throw new IllegalArgumentException("overlap must be >= 0");
        }
        if (Float.isNaN(img2imgStrength) || Float.isInfinite(img2imgStrength) || img2imgStrength < 0.0f || img2imgStrength > 1.0f) {
            throw new IllegalArgumentException("img2imgStrength must be between 0.0 and 1.0");
        }
    }
}
