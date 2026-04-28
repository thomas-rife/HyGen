package com.example.exampleplugin.terrain;

import com.hypixel.hytale.math.vector.Transform;

import javax.annotation.Nonnull;

public final class SafeSpawnFinder {
    @Nonnull
    public SpawnSelection findSpawn(@Nonnull TerrainPackage terrainPackage, int originX, int originZ) {
        int centerX = terrainPackage.width() / 2;
        int centerZ = terrainPackage.depth() / 2;
        SpawnSelection best = null;
        int bestScore = Integer.MAX_VALUE;

        for (int radius = 0; radius < Math.max(terrainPackage.width(), terrainPackage.depth()); radius++) {
            for (int x = Math.max(0, centerX - radius); x <= Math.min(terrainPackage.width() - 1, centerX + radius); x++) {
                for (int z = Math.max(0, centerZ - radius); z <= Math.min(terrainPackage.depth() - 1, centerZ + radius); z++) {
                    if (Math.max(Math.abs(x - centerX), Math.abs(z - centerZ)) != radius) {
                        continue;
                    }
                    if (terrainPackage.isWaterAt(x, z)) {
                        continue;
                    }
                    int slope = localSlope(terrainPackage, x, z);
                    if (slope > AiTerrainSettings.MAX_TREE_SLOPE_DELTA) {
                        continue;
                    }
                    int score = (Math.abs(x - centerX) + Math.abs(z - centerZ)) * 10 + slope;
                    if (best == null || score < bestScore) {
                        int y = terrainPackage.heightAt(x, z);
                        best = new SpawnSelection(x, z, y + 1, false);
                        bestScore = score;
                    }
                }
            }
            if (best != null) {
                break;
            }
        }

        if (best == null) {
            best = new SpawnSelection(centerX, centerZ, terrainPackage.metadata().seaLevel + 2, true);
        }

        return new SpawnSelection(
            best.localX(),
            best.localZ(),
            best.y(),
            best.requiresPlatform(),
            new Transform(originX + best.localX() + 0.5D, best.y(), originZ + best.localZ() + 0.5D)
        );
    }

    private static int localSlope(@Nonnull TerrainPackage terrainPackage, int x, int z) {
        int center = terrainPackage.heightAt(x, z);
        int min = center;
        int max = center;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int nx = x + dx;
                int nz = z + dz;
                if (nx < 0 || nz < 0 || nx >= terrainPackage.width() || nz >= terrainPackage.depth()) {
                    continue;
                }
                if (terrainPackage.isWaterAt(nx, nz)) {
                    return Integer.MAX_VALUE;
                }
                int height = terrainPackage.heightAt(nx, nz);
                min = Math.min(min, height);
                max = Math.max(max, height);
            }
        }
        return max - min;
    }

    public record SpawnSelection(int localX, int localZ, int y, boolean requiresPlatform, @Nonnull Transform transform) {
        public SpawnSelection(int localX, int localZ, int y, boolean requiresPlatform) {
            this(localX, localZ, y, requiresPlatform, new Transform(localX + 0.5D, y, localZ + 0.5D));
        }
    }
}
