package com.example.exampleplugin.terrain;

import com.example.exampleplugin.terrain.TerrainBiomeClassifier.TerrainBiomeProfile;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.prefab.selection.standard.BlockSelection;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

public final class TerrainPlacer {
    private static final int BLOCK_UPDATE_SETTINGS = 512 | 4 | 2 | 16 | 8;
    private static final int SPAWN_DECORATION_EXCLUSION_RADIUS = 6;
    private static final int LARGE_PREFAB_SPAWN_CLEAR_RADIUS = 40;
    private static final HytaleLogger LOGGER = HytaleLogger.get("HyGen|AiTerrain");

    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(@Nonnull String stage, int completedTiles, int totalTiles, int affectedCount);
    }

    @Nonnull
    public CompletableFuture<Integer> placeTerrainAsync(
        @Nonnull World targetWorld,
        @Nonnull TerrainPackage terrainPackage,
        int originX,
        int originZ,
        int batchSize,
        @Nonnull AiTerrainPlacementMode placementMode,
        int surfaceShellDepth
    ) {
        return placeTerrainAsync(targetWorld, terrainPackage, originX, originZ, batchSize, placementMode, surfaceShellDepth, null);
    }

    @Nonnull
    public CompletableFuture<Integer> placeTerrainAsync(
        @Nonnull World targetWorld,
        @Nonnull TerrainPackage terrainPackage,
        int originX,
        int originZ,
        int batchSize,
        @Nonnull AiTerrainPlacementMode placementMode,
        int surfaceShellDepth,
        ProgressListener progressListener
    ) {
        TerrainBlockPalette palette = new TerrainBlockPalette(terrainPackage);
        return runTiles(targetWorld, terrainPackage, batchSize, "terrain", progressListener, (startX, startZ) ->
            placeTerrainTile(targetWorld, originX, originZ, terrainPackage, palette, startX, startZ, batchSize, placementMode, surfaceShellDepth)
        );
    }

    @Nonnull
    public CompletableFuture<Integer> placeWaterAsync(
        @Nonnull World targetWorld,
        @Nonnull TerrainPackage terrainPackage,
        int originX,
        int originZ,
        int batchSize
    ) {
        return placeWaterAsync(targetWorld, terrainPackage, originX, originZ, batchSize, null);
    }

    @Nonnull
    public CompletableFuture<Integer> placeWaterAsync(
        @Nonnull World targetWorld,
        @Nonnull TerrainPackage terrainPackage,
        int originX,
        int originZ,
        int batchSize,
        ProgressListener progressListener
    ) {
        TerrainBlockPalette palette = new TerrainBlockPalette(terrainPackage);
        LOGGER.at(Level.INFO).log("Water placement start: waterheight=%s seaLevel=%s", terrainPackage.hasWaterHeightmap(), terrainPackage.metadata().seaLevel);
        return runTiles(targetWorld, terrainPackage, batchSize, "water", progressListener, (startX, startZ) ->
            placeWaterTile(targetWorld, originX, originZ, terrainPackage, palette, startX, startZ, batchSize)
        ).thenApply(waterColumns -> {
            LOGGER.at(Level.INFO).log("Water placement complete: waterColumns=%s block=%s", waterColumns, "Fluid_Water");
            return waterColumns;
        });
    }

    @Nonnull
    public CompletableFuture<Integer> placeDecorationsAsync(
        @Nonnull World targetWorld,
        @Nonnull TerrainPackage terrainPackage,
        int originX,
        int originZ,
        @Nonnull SafeSpawnFinder.SpawnSelection spawnSelection
    ) {
        return CompletableFuture.supplyAsync(() -> {
            DecorationPlacer decorationPlacer = new DecorationPlacer(targetWorld, originX, originZ, terrainPackage, new TerrainBlockPalette(terrainPackage), spawnSelection);
            return decorationPlacer.place();
        }, targetWorld);
    }

    @Nonnull
    public CompletableFuture<Void> finalizeSpawnAsync(
        @Nonnull World targetWorld,
        int originX,
        int originZ,
        @Nonnull TerrainPackage terrainPackage,
        @Nonnull SafeSpawnFinder.SpawnSelection spawnSelection
    ) {
        return CompletableFuture.runAsync(() -> {
            int worldX = originX + spawnSelection.localX();
            int worldZ = originZ + spawnSelection.localZ();
            TerrainMaterialSelection spawnMaterial = new TerrainBlockPalette(terrainPackage).coverSurfaceForSpawn(worldX, worldZ);
            int groundY = spawnSelection.y() - 1;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    int px = worldX + dx;
                    int pz = worldZ + dz;
                    WorldChunk chunk = chunkAt(targetWorld, px, pz);
                    chunk.setBlock(px, groundY, pz, spawnMaterial.surfaceId, spawnMaterial.surfaceType, 0, 0, BLOCK_UPDATE_SETTINGS);
                    chunk.setBlock(px, groundY - 1, pz, spawnMaterial.foundationId, spawnMaterial.foundationType, 0, 0, BLOCK_UPDATE_SETTINGS);
                }
            }
        }, targetWorld);
    }

    @Nonnull
    private CompletableFuture<Integer> runTiles(
        @Nonnull World targetWorld,
        @Nonnull TerrainPackage terrainPackage,
        int batchSize,
        @Nonnull String stage,
        ProgressListener progressListener,
        @Nonnull TilePlacer tilePlacer
    ) {
        AtomicInteger affected = new AtomicInteger();
        AtomicInteger completedTiles = new AtomicInteger();
        int tilesX = (terrainPackage.width() + batchSize - 1) / batchSize;
        int tilesZ = (terrainPackage.depth() + batchSize - 1) / batchSize;
        int totalTiles = tilesX * tilesZ;
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (int z = 0; z < terrainPackage.depth(); z += batchSize) {
            for (int x = 0; x < terrainPackage.width(); x += batchSize) {
                int startZ = z;
                int startX = x;
                chain = chain.thenRunAsync(() -> {
                    affected.addAndGet(tilePlacer.place(startX, startZ));
                    int finished = completedTiles.incrementAndGet();
                    if (progressListener != null
                        && (finished == 1 || finished == totalTiles || finished % AiTerrainSettings.PLACEMENT_PROGRESS_EVERY_TILES == 0)) {
                        progressListener.onProgress(stage, finished, totalTiles, affected.get());
                    }
                }, targetWorld);
            }
        }
        return chain.thenApply(ignored -> affected.get());
    }

    private int placeTerrainTile(
        @Nonnull World targetWorld,
        int originX,
        int originZ,
        @Nonnull TerrainPackage terrainPackage,
        @Nonnull TerrainBlockPalette palette,
        int startX,
        int startZ,
        int batchSize,
        @Nonnull AiTerrainPlacementMode placementMode,
        int surfaceShellDepth
    ) {
        int placedColumns = 0;
        int maxZ = Math.min(terrainPackage.depth(), startZ + batchSize);
        for (int z = startZ; z < maxZ; z++) {
            int maxX = Math.min(terrainPackage.width(), startX + batchSize);
            for (int x = startX; x < maxX; x++) {
                int worldX = originX + x;
                int worldZ = originZ + z;
                int surfaceY = blendedHeight(terrainPackage, x, z);
                int slope = localSlope(terrainPackage, x, z);
                TerrainMaterialSelection material = palette.resolve(
                    terrainPackage.materialIdAt(x, z),
                    terrainPackage.materialNameAt(x, z),
                    terrainPackage.isWaterAt(x, z),
                    worldX,
                    worldZ,
                    surfaceY,
                    slope
                );
                placeSolidColumn(
                    chunkAt(targetWorld, worldX, worldZ),
                    terrainPackage,
                    x,
                    z,
                    worldX,
                    worldZ,
                    surfaceY,
                    material,
                    placementMode,
                    surfaceShellDepth
                );
                placedColumns++;
            }
        }
        return placedColumns;
    }

    private int placeWaterTile(
        @Nonnull World targetWorld,
        int originX,
        int originZ,
        @Nonnull TerrainPackage terrainPackage,
        @Nonnull TerrainBlockPalette palette,
        int startX,
        int startZ,
        int batchSize
    ) {
        int waterColumns = 0;
        int maxZ = Math.min(terrainPackage.depth(), startZ + batchSize);
        for (int z = startZ; z < maxZ; z++) {
            int maxX = Math.min(terrainPackage.width(), startX + batchSize);
            for (int x = startX; x < maxX; x++) {
                if (!terrainPackage.isWaterAt(x, z)) {
                    continue;
                }
                int worldX = originX + x;
                int worldZ = originZ + z;
                int terrainY = blendedHeight(terrainPackage, x, z);
                TerrainMaterialSelection material = palette.resolve(
                    terrainPackage.materialIdAt(x, z),
                    terrainPackage.materialNameAt(x, z),
                    true,
                    worldX,
                    worldZ,
                    terrainY,
                    localSlope(terrainPackage, x, z)
                );
                fillWaterColumn(chunkAt(targetWorld, worldX, worldZ), worldX, worldZ, terrainY, terrainPackage.waterHeightAt(x, z), material.fluidId, material.fluidType);
                waterColumns++;
            }
        }
        return waterColumns;
    }

    private static int fillWaterColumn(
        @Nonnull WorldChunk chunk,
        int worldX,
        int worldZ,
        int terrainY,
        int targetWaterY,
        int waterId,
        @Nonnull BlockType waterType
    ) {
        int startY = Math.max(0, terrainY + 1);
        int endY = targetWaterY > terrainY
            ? Math.min(AiTerrainSettings.MAX_PLACEMENT_HEIGHT, targetWaterY)
            : startY;
        for (int y = startY; y <= endY; y++) {
            chunk.setBlock(worldX, y, worldZ, waterId, waterType, 0, 0, BLOCK_UPDATE_SETTINGS);
        }
        return Math.max(0, endY - startY + 1);
    }

    private static void placeSolidColumn(
        @Nonnull WorldChunk chunk,
        @Nonnull TerrainPackage terrainPackage,
        int localX,
        int localZ,
        int worldX,
        int worldZ,
        int height,
        @Nonnull TerrainMaterialSelection material,
        @Nonnull AiTerrainPlacementMode placementMode,
        int surfaceShellDepth
    ) {
        int startY = switch (placementMode) {
            case FULL_COLUMN -> 0;
            case SURFACE_SHELL -> shellStartY(terrainPackage, localX, localZ, height, Math.max(surfaceShellDepth, material.topLayers + 1));
        };
        for (int y = startY; y <= height; y++) {
            if (y == height) {
                chunk.setBlock(worldX, y, worldZ, material.surfaceId, material.surfaceType, 0, 0, BLOCK_UPDATE_SETTINGS);
            } else if (y >= height - material.topLayers + 1) {
                chunk.setBlock(worldX, y, worldZ, material.subsurfaceId, material.subsurfaceType, 0, 0, BLOCK_UPDATE_SETTINGS);
            } else {
                chunk.setBlock(worldX, y, worldZ, material.foundationId, material.foundationType, 0, 0, BLOCK_UPDATE_SETTINGS);
            }
        }
    }

    private static int shellStartY(
        @Nonnull TerrainPackage terrainPackage,
        int x,
        int z,
        int surfaceY,
        int shellDepth
    ) {
        int fixedDepthStartY = Math.max(0, surfaceY - shellDepth + 1);
        int lowestAdjacent = surfaceY;
        boolean foundLowerNeighbor = false;
        int[][] offsets = {
            {1, 0},
            {-1, 0},
            {0, 1},
            {0, -1}
        };
        for (int[] offset : offsets) {
            int nx = x + offset[0];
            int nz = z + offset[1];
            if (nx < 0 || nz < 0 || nx >= terrainPackage.width() || nz >= terrainPackage.depth()) {
                continue;
            }
            int neighborY = blendedHeight(terrainPackage, nx, nz);
            if (neighborY < surfaceY) {
                lowestAdjacent = Math.min(lowestAdjacent, neighborY);
                foundLowerNeighbor = true;
            }
        }

        if (!foundLowerNeighbor) {
            return fixedDepthStartY;
        }

        int supportedStartY = Math.max(0, lowestAdjacent + 1);
        return Math.min(fixedDepthStartY, supportedStartY);
    }

    static int blendedHeight(@Nonnull TerrainPackage terrainPackage, int x, int z) {
        TerrainPackage.Metadata metadata = terrainPackage.metadata();
        int rawHeight = terrainPackage.heightAt(x, z);
        int clamped = Math.max(0, Math.min(AiTerrainSettings.MAX_PLACEMENT_HEIGHT, rawHeight));
        if (metadata.arenaSize <= 0) {
            return clamped;
        }

        float centerX = (terrainPackage.width() - 1) / 2.0f;
        float centerZ = (terrainPackage.depth() - 1) / 2.0f;
        float dx = Math.abs(x - centerX);
        float dz = Math.abs(z - centerZ);
        float chebyshev = Math.max(dx, dz);
        float arenaRadius = metadata.arenaSize / 2.0f;
        if (chebyshev <= arenaRadius) {
            return metadata.baseY;
        }

        int feather = metadata.arenaFeather;
        if (feather <= 0 || chebyshev >= arenaRadius + feather) {
            return clamped;
        }

        float t = (chebyshev - arenaRadius) / feather;
        return Math.max(0, Math.min(AiTerrainSettings.MAX_PLACEMENT_HEIGHT, Math.round(metadata.baseY + (clamped - metadata.baseY) * t)));
    }

    static int localSlope(@Nonnull TerrainPackage terrainPackage, int x, int z) {
        int center = blendedHeight(terrainPackage, x, z);
        int min = center;
        int max = center;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int nx = x + dx;
                int nz = z + dz;
                if (nx < 0 || nz < 0 || nx >= terrainPackage.width() || nz >= terrainPackage.depth()) {
                    continue;
                }
                int neighbor = blendedHeight(terrainPackage, nx, nz);
                min = Math.min(min, neighbor);
                max = Math.max(max, neighbor);
            }
        }
        return max - min;
    }

    @Nonnull
    private static WorldChunk chunkAt(@Nonnull World targetWorld, int worldX, int worldZ) {
        return targetWorld.getChunk(ChunkUtil.indexChunkFromBlock(worldX, worldZ));
    }

    private interface TilePlacer {
        int place(int startX, int startZ);
    }

    private static final class DecorationPlacer {
        private final World targetWorld;
        private final int originX;
        private final int originZ;
        private final TerrainPackage terrainPackage;
        private final TerrainBlockPalette palette;
        private final TerrainBiomeProfile biomeProfile;
        private final SafeSpawnFinder.SpawnSelection spawnSelection;
        private final TerrainPrefabLibrary prefabLibrary;
        private final String promptLower;
        private final Map<String, Integer> coverCounts = new LinkedHashMap<>();
        private final Map<String, Integer> treeCounts = new LinkedHashMap<>();
        private final Map<String, Integer> naturalCounts = new LinkedHashMap<>();
        private final List<int[]> placedTreePoints = new ArrayList<>();
        private final List<int[]> placedNaturalPoints = new ArrayList<>();
        private int skippedProtected;
        private int skippedSlope;
        private int skippedWater;

        private DecorationPlacer(
            @Nonnull World targetWorld,
            int originX,
            int originZ,
            @Nonnull TerrainPackage terrainPackage,
            @Nonnull TerrainBlockPalette palette,
            @Nonnull SafeSpawnFinder.SpawnSelection spawnSelection
        ) {
            this.targetWorld = targetWorld;
            this.originX = originX;
            this.originZ = originZ;
            this.terrainPackage = terrainPackage;
            this.palette = palette;
            this.biomeProfile = palette.biomeProfile();
            this.spawnSelection = spawnSelection;
            this.prefabLibrary = new TerrainPrefabLibrary();
            this.promptLower = terrainPackage.metadata().prompt == null ? "" : terrainPackage.metadata().prompt.toLowerCase(Locale.ROOT);
        }

        private int place() {
            int total = 0;
            total += placeIntentDecorations();
            total += placeCoverPass();
            total += placeTreePrefabs();
            total += placeNaturalPrefabs();
            logReport();
            return total;
        }

        private int placeIntentDecorations() {
            int placed = 0;
            for (TerrainPackage.DecorationIntent decoration : this.terrainPackage.decorations()) {
                if (placeIntent(decoration)) {
                    placed++;
                }
            }
            return placed;
        }

        private boolean placeIntent(@Nonnull TerrainPackage.DecorationIntent decoration) {
            if (decoration.type == null || decoration.type.isBlank()) {
                return false;
            }
            int x = decoration.x;
            int z = decoration.z;
            if (!inBounds(x, z)) {
                return false;
            }
            int slope = localSlope(this.terrainPackage, x, z);
            if (isProtected(x, z, false)) {
                this.skippedProtected++;
                return false;
            }
            String normalized = normalize(decoration.type);
            if (normalized.startsWith("tree")) {
                return placeTreeAt(x, z, slope, true);
            }
            if (normalized.startsWith("rock") || normalized.equals("boulder")) {
                return placeNaturalAt(x, z, slope, preferredNaturalPaths(), 8, true, false, false, 0, 0);
            }
            return placeCoverAt(x, z, slope, true);
        }

        private int placeCoverPass() {
            int placed = 0;
            for (int z = 0; z < this.terrainPackage.depth(); z++) {
                for (int x = 0; x < this.terrainPackage.width(); x++) {
                    int slope = localSlope(this.terrainPackage, x, z);
                    if (placeCoverAt(x, z, slope, false)) {
                        placed++;
                    }
                }
            }
            return placed;
        }

        private boolean placeCoverAt(int x, int z, int slope, boolean fromIntent) {
            boolean water = this.terrainPackage.isWaterAt(x, z);
            if (isProtected(x, z, false)) {
                this.skippedProtected++;
                return false;
            }
            int worldX = this.originX + x;
            int worldZ = this.originZ + z;
            int surfaceY = blendedHeight(this.terrainPackage, x, z);
            if (water) {
                return placeWaterCover(x, z, worldX, worldZ, surfaceY, fromIntent);
            }

            String coverId = chooseLandCover(x, z, slope, fromIntent);
            if (coverId == null) {
                return false;
            }
            if (slope > AiTerrainSettings.MAX_DECORATION_SLOPE_DELTA && !isSlopeSafeCover(coverId)) {
                this.skippedSlope++;
                return false;
            }
            if (this.targetWorld.getBlock(worldX, surfaceY + 1, worldZ) != 0) {
                return false;
            }
            if (!hasAirColumn(worldX, surfaceY + 1, worldZ, 2)) {
                return false;
            }
            return placeCoverBlock(coverId, worldX, surfaceY + 1, worldZ);
        }

        private boolean placeWaterCover(int x, int z, int worldX, int worldZ, int surfaceY, boolean fromIntent) {
            int depth = Math.max(0, this.terrainPackage.waterHeightAt(x, z) - surfaceY);
            if (depth <= 0) {
                this.skippedWater++;
                return false;
            }
            String coverId = null;
            int placeY = surfaceY + 1;
            if (this.biomeProfile.primary() == TerrainBiomeProfile.PrimaryBiome.SWAMP && depth <= 3 && isNearWaterEdge(x, z)) {
                coverId = pick(hash(worldX, worldZ, 19), "Plant_Reeds_Marsh", "Plant_Reeds_Water", "Plant_Flower_Water_Duckweed");
                if ("Plant_Flower_Water_Duckweed".equals(coverId)) {
                    placeY = this.terrainPackage.waterHeightAt(x, z);
                }
            } else if ((this.biomeProfile.hasLake() || this.biomeProfile.hasRiver() || this.biomeProfile.hasBeach()) && depth >= 2 && depth <= 6) {
                coverId = pick(hash(worldX, worldZ, 23), "Plant_Seaweed_Grass", "Plant_Seaweed_Grass_Tall", "Plant_Seaweed_Grass");
            }
            if (coverId == null || (!fromIntent && !passesDensity(coverDensity(x, z, true), worldX, worldZ, 31))) {
                return false;
            }
            if (this.targetWorld.getBlock(worldX, placeY, worldZ) != 0) {
                return false;
            }
            return placeCoverBlock(coverId, worldX, placeY, worldZ);
        }

        private String chooseLandCover(int x, int z, int slope, boolean fromIntent) {
            int worldX = this.originX + x;
            int worldZ = this.originZ + z;
            if (!fromIntent && !passesDensity(coverDensity(x, z, false), worldX, worldZ, 7)) {
                return null;
            }
            return switch (this.biomeProfile.primary()) {
                case FOREST -> pick(hash(worldX, worldZ, 1), "Plant_Fern_Forest", "Plant_Moss_Rug_Green", "Plant_Crop_Mushroom_Cap_Brown", "Plant_Crop_Mushroom_Cap_Red");
                case SWAMP -> isNearWaterEdge(x, z) ? pick(hash(worldX, worldZ, 2), "Plant_Reeds_Marsh", "Plant_Reeds_Water", "Plant_Grass_Lush_Short") : null;
                case DESERT, CANYON_UTAH -> pick(hash(worldX, worldZ, 3), "Plant_Grass_Arid", "Plant_Bush_Arid", "Plant_Bush_Dead_Twisted", "Plant_Cactus_Ball_1", "Plant_Cactus_1");
                case SNOWY_ALPINE -> pick(hash(worldX, worldZ, 4), "Plant_Grass_Winter", "Plant_Fern_Winter", slope > 4 ? "Soil_Pebbles" : "Plant_Grass_Winter");
                case ROCKY_MOUNTAIN -> pick(hash(worldX, worldZ, 5), "Rubble_Stone", "Rubble_Stone_Medium", "Soil_Pebbles");
                case VOLCANIC -> pick(hash(worldX, worldZ, 6), "Plant_Bramble_Dead_Lavathorn", "Rubble_Stone", "Soil_Pebbles");
                default -> pick(hash(worldX, worldZ, 0), "Plant_Grass_Lush_Tall", "Plant_Grass_Lush_Short", "Plant_Flower_Common_Yellow", "Plant_Flower_Common_White");
            };
        }

        private double coverDensity(int x, int z, boolean water) {
            if (water) {
                return this.biomeProfile.primary() == TerrainBiomeProfile.PrimaryBiome.SWAMP ? 0.18D : 0.08D;
            }
            if (isNearWaterEdge(x, z) && this.biomeProfile.primary() == TerrainBiomeProfile.PrimaryBiome.SWAMP) {
                return 0.20D;
            }
            return switch (this.biomeProfile.primary()) {
                case FOREST -> 0.10D;
                case SWAMP -> 0.05D;
                case DESERT, CANYON_UTAH -> 0.015D;
                case SNOWY_ALPINE -> 0.04D;
                case ROCKY_MOUNTAIN, VOLCANIC -> 0.03D;
                default -> 0.08D;
            };
        }

        private int placeTreePrefabs() {
            int placed = 0;
            for (PrefabRule rule : treeRules()) {
                int attempts = Math.max(1, (int) Math.round(this.terrainPackage.width() * this.terrainPackage.depth() * rule.density));
                for (int attempt = 0; attempt < attempts; attempt++) {
                    int[] candidate = sampleCandidate(attempt, rule.path.hashCode());
                    if (placeTreeAt(candidate[0], candidate[1], localSlope(this.terrainPackage, candidate[0], candidate[1]), false, rule)) {
                        placed++;
                    }
                }
            }
            return placed;
        }

        private boolean placeTreeAt(int x, int z, int slope, boolean fromIntent) {
            PrefabRule rule = treeRules().get(hash(this.originX + x, this.originZ + z, 41) % treeRules().size());
            return placeTreeAt(x, z, slope, fromIntent, rule);
        }

        private boolean placeTreeAt(int x, int z, int slope, boolean fromIntent, @Nonnull PrefabRule rule) {
            if (!inBounds(x, z)) {
                return false;
            }
            if (this.terrainPackage.isWaterAt(x, z)) {
                this.skippedWater++;
                return false;
            }
            if (isProtected(x, z, true)) {
                this.skippedProtected++;
                return false;
            }
            if (slope > rule.maxSlope) {
                this.skippedSlope++;
                return false;
            }
            if (!fromIntent && tooCloseToPlaced(this.placedTreePoints, x, z, rule.minSpacing)) {
                return false;
            }
            int worldX = this.originX + x;
            int worldZ = this.originZ + z;
            int surfaceY = blendedHeight(this.terrainPackage, x, z);
            if (!hasAirColumn(worldX, surfaceY + 1, worldZ, 8)) {
                return false;
            }
            if (!footprintOkay(x, z, rule.footprintRadius, rule.maxSlope + 1, false)) {
                this.skippedSlope++;
                return false;
            }
            BlockSelection selection = this.prefabLibrary.load(rule.path, hash(worldX, worldZ, 47), hash(worldX, worldZ, 53) % 4);
            if (selection == null) {
                return false;
            }
            this.prefabLibrary.place(this.targetWorld, selection, worldX, surfaceY, worldZ);
            this.placedTreePoints.add(new int[] {x, z});
            increment(this.treeCounts, rule.path);
            return true;
        }

        private int placeNaturalPrefabs() {
            int placed = 0;
            for (PrefabRule rule : naturalRules()) {
                int attempts = Math.max(1, (int) Math.round(this.terrainPackage.width() * this.terrainPackage.depth() * rule.density));
                for (int attempt = 0; attempt < attempts; attempt++) {
                    int[] candidate = sampleCandidate(attempt, rule.path.hashCode() ^ 97);
                    if (placeNaturalAt(candidate[0], candidate[1], localSlope(this.terrainPackage, candidate[0], candidate[1]), List.of(rule.path), rule.minSpacing, false, rule.requiresWater, rule.shoreOnly, rule.minWaterDepth, rule.maxWaterDepth)) {
                        placed++;
                    }
                }
            }
            return placed;
        }

        private boolean placeNaturalAt(
            int x,
            int z,
            int slope,
            @Nonnull List<String> paths,
            int minSpacing,
            boolean fromIntent,
            boolean requiresWater,
            boolean shoreOnly,
            int minWaterDepth,
            int maxWaterDepth
        ) {
            if (!inBounds(x, z) || paths.isEmpty()) {
                return false;
            }
            boolean water = this.terrainPackage.isWaterAt(x, z);
            if (requiresWater != water) {
                this.skippedWater++;
                return false;
            }
            if (shoreOnly && !isNearWaterEdge(x, z)) {
                return false;
            }
            if (isProtected(x, z, true)) {
                this.skippedProtected++;
                return false;
            }
            if (!fromIntent && tooCloseToPlaced(this.placedNaturalPoints, x, z, minSpacing)) {
                return false;
            }
            int depth = Math.max(0, this.terrainPackage.waterHeightAt(x, z) - blendedHeight(this.terrainPackage, x, z));
            if ((minWaterDepth > 0 && depth < minWaterDepth) || (maxWaterDepth > 0 && depth > maxWaterDepth)) {
                return false;
            }
            if (!requiresWater && slope > 6) {
                this.skippedSlope++;
                return false;
            }
            int worldX = this.originX + x;
            int worldZ = this.originZ + z;
            int surfaceY = blendedHeight(this.terrainPackage, x, z);
            String path = paths.get(hash(worldX, worldZ, 59) % paths.size());
            BlockSelection selection = this.prefabLibrary.load(path, hash(worldX, worldZ, 61), hash(worldX, worldZ, 67) % 4);
            if (selection == null) {
                return false;
            }
            int footprintRadius = Math.max(2, Math.max(selection.getSelectionMax().getX() - selection.getSelectionMin().getX(), selection.getSelectionMax().getZ() - selection.getSelectionMin().getZ()) / 2);
            if (!footprintOkay(x, z, footprintRadius, requiresWater ? 3 : 6, requiresWater)) {
                return false;
            }
            this.prefabLibrary.place(this.targetWorld, selection, worldX, surfaceY, worldZ);
            this.placedNaturalPoints.add(new int[] {x, z});
            increment(this.naturalCounts, path);
            return true;
        }

        @Nonnull
        private List<PrefabRule> treeRules() {
            List<PrefabRule> rules = new ArrayList<>();
            switch (this.biomeProfile.primary()) {
                case FOREST -> {
                    rules.add(new PrefabRule("Trees/Oak/Stage_5/", 0.0045D, 8, 4, 3, false, false, 0, 0));
                    rules.add(new PrefabRule("Trees/Birch/", 0.0025D, 8, 4, 3, false, false, 0, 0));
                    rules.add(new PrefabRule("Trees/Maple/", 0.0018D, 9, 4, 4, false, false, 0, 0));
                    rules.add(new PrefabRule("Trees/Oak/Stage_1/", 0.0015D, 6, 4, 2, false, false, 0, 0));
                }
                case SNOWY_ALPINE -> rules.add(new PrefabRule("Trees/Fir_Snow/", 0.0016D, 10, 4, 4, false, false, 0, 0));
                case SWAMP -> {
                    rules.add(new PrefabRule("Trees/Willow/Stage_3/", 0.0028D, 9, 3, 4, false, false, 0, 0));
                    rules.add(new PrefabRule("Trees/Ash_Dead/", 0.0015D, 10, 3, 4, false, false, 0, 0));
                }
                case BEACH_COAST, TROPICAL_ISLAND -> rules.add(new PrefabRule("Trees/Palm_Green/Stage_2/", this.biomeProfile.primary() == TerrainBiomeProfile.PrimaryBiome.TROPICAL_ISLAND ? 0.0045D : 0.0025D, 10, 4, 4, false, false, 0, 0));
                case DESERT -> {
                    rules.add(new PrefabRule("Trees/Palm/Stage_2/", 0.0005D, 14, 3, 4, false, false, 0, 0));
                    rules.add(new PrefabRule("Plants/Cacti/", 0.0008D, 9, 3, 2, false, false, 0, 0));
                }
                case CANYON_UTAH -> {
                    rules.add(new PrefabRule("Plants/Cacti/", 0.0008D, 9, 3, 2, false, false, 0, 0));
                    if (this.promptLower.contains("dead") || this.promptLower.contains("dry")) {
                        rules.add(new PrefabRule("Trees/Ash_Dead/", 0.0003D, 12, 3, 4, false, false, 0, 0));
                    }
                }
                case VOLCANIC -> {
                    rules.add(new PrefabRule("Trees/Burnt/", 0.0005D, 12, 3, 4, false, false, 0, 0));
                    rules.add(new PrefabRule("Trees/Petrified/Stage_2/", 0.0004D, 12, 3, 4, false, false, 0, 0));
                }
                case PLAINS_MEADOW, DEFAULT -> {
                    if (this.promptLower.contains("savanna")) {
                        rules.add(new PrefabRule("Trees/Boab/", 0.0012D, 12, 3, 4, false, false, 0, 0));
                    } else {
                        rules.add(new PrefabRule("Trees/Oak/Stage_1/", 0.0010D, 9, 4, 3, false, false, 0, 0));
                        rules.add(new PrefabRule("Trees/Oak/Stage_5/", 0.0005D, 12, 4, 4, false, false, 0, 0));
                    }
                }
                case ROCKY_MOUNTAIN -> rules.add(new PrefabRule("Trees/Fir_Snow/", 0.0008D, 12, 3, 4, false, false, 0, 0));
            }
            if ((this.promptLower.contains("cave") || this.promptLower.contains("mushroom") || this.promptLower.contains("fantasy")) && !this.biomeProfile.hasBeach()) {
                rules.add(new PrefabRule("Plants/Mushroom_Large/", 0.0006D, 10, 3, 3, false, false, 0, 0));
            }
            return rules;
        }

        @Nonnull
        private List<PrefabRule> naturalRules() {
            List<PrefabRule> rules = new ArrayList<>();
            switch (this.biomeProfile.primary()) {
                case DESERT, CANYON_UTAH -> {
                    rules.add(new PrefabRule("Rock_Formations/Rocks/Sandstone/Large/", 0.0010D, 16, 4, 6, false, false, 0, 0));
                    rules.add(new PrefabRule("Rock_Formations/Rocks/Sandstone/Pillars/", 0.0006D, 18, 4, 8, false, false, 0, 0));
                }
                case VOLCANIC -> {
                    rules.add(new PrefabRule("Rock_Formations/Rocks/Basalt/", 0.0010D, 14, 4, 6, false, false, 0, 0));
                    rules.add(new PrefabRule("Rock_Formations/Rocks/Spikes/", 0.0005D, 18, 4, 8, false, false, 0, 0));
                }
                case SNOWY_ALPINE, ROCKY_MOUNTAIN -> {
                    rules.add(new PrefabRule("Rock_Formations/Ice_Formations/", 0.0008D, 14, 4, 6, false, false, 0, 0));
                    rules.add(new PrefabRule("Rock_Formations/Rocks/Stone/Small/", 0.0014D, 10, 4, 4, false, false, 0, 0));
                }
                case FOREST -> {
                    rules.add(new PrefabRule("Trees/Fir_Logs/", 0.0010D, 10, 4, 4, false, false, 0, 0));
                    rules.add(new PrefabRule("Trees/Oak/Stumps/", 0.0011D, 8, 4, 3, false, false, 0, 0));
                    rules.add(new PrefabRule("Rock_Formations/Rocks/Stone/Small/", 0.0008D, 9, 4, 3, false, false, 0, 0));
                }
                case BEACH_COAST, TROPICAL_ISLAND -> {
                    rules.add(new PrefabRule("Plants/Driftwood/", 0.0012D, 10, 4, 4, false, true, 0, 0));
                    rules.add(new PrefabRule("Plants/Coral/", 0.0008D, 8, 3, 4, true, false, 2, 7));
                    rules.add(new PrefabRule("Plants/Seaweed/", 0.0012D, 8, 3, 4, true, false, 2, 6));
                }
                default -> rules.add(new PrefabRule("Rock_Formations/Rocks/Stone/Small/", 0.0012D, 10, 4, 4, false, false, 0, 0));
            }
            if (this.biomeProfile.hasCaveFantasy() || this.biomeProfile.hasAncientRare()) {
                rules.add(new PrefabRule("Rock_Formations/Crystals/", 0.00015D, 18, 4, 5, false, false, 0, 0));
                rules.add(new PrefabRule("Rock_Formations/Fossils/", 0.00012D, 18, 4, 5, false, false, 0, 0));
                rules.add(new PrefabRule("Rock_Formations/Dolmen/", 0.00008D, 20, 4, 8, false, false, 0, 0));
            }
            return rules;
        }

        @Nonnull
        private List<String> preferredNaturalPaths() {
            List<PrefabRule> rules = naturalRules();
            List<String> paths = new ArrayList<>(rules.size());
            for (PrefabRule rule : rules) {
                paths.add(rule.path);
            }
            return paths;
        }

        private boolean placeCoverBlock(@Nonnull String blockName, int worldX, int worldY, int worldZ) {
            int blockId = TerrainBlockPalette.blockId(blockName, "Plant_Grass_Lush_Short");
            BlockType blockType = BlockType.getAssetMap().getAsset(blockId);
            if (blockType == null) {
                return false;
            }
            chunkAt(this.targetWorld, worldX, worldZ).setBlock(worldX, worldY, worldZ, blockId, blockType, 0, 0, BLOCK_UPDATE_SETTINGS);
            increment(this.coverCounts, blockName);
            return true;
        }

        private boolean hasAirColumn(int worldX, int startY, int worldZ, int height) {
            for (int y = startY; y < startY + height; y++) {
                if (this.targetWorld.getBlock(worldX, y, worldZ) != 0) {
                    return false;
                }
            }
            return true;
        }

        private boolean footprintOkay(int x, int z, int radius, int maxSlope, boolean requiresWater) {
            int min = Integer.MAX_VALUE;
            int max = Integer.MIN_VALUE;
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    int nx = x + dx;
                    int nz = z + dz;
                    if (!inBounds(nx, nz)) {
                        return false;
                    }
                    if (requiresWater != this.terrainPackage.isWaterAt(nx, nz)) {
                        return false;
                    }
                    int height = blendedHeight(this.terrainPackage, nx, nz);
                    min = Math.min(min, height);
                    max = Math.max(max, height);
                }
            }
            return max - min <= maxSlope;
        }

        private boolean isProtected(int x, int z, boolean largePrefab) {
            int dx = Math.abs(x - this.spawnSelection.localX());
            int dz = Math.abs(z - this.spawnSelection.localZ());
            int spawnRadius = largePrefab ? LARGE_PREFAB_SPAWN_CLEAR_RADIUS : SPAWN_DECORATION_EXCLUSION_RADIUS;
            if (Math.max(dx, dz) <= spawnRadius) {
                return true;
            }
            TerrainPackage.Metadata metadata = this.terrainPackage.metadata();
            if (metadata.arenaSize > 0) {
                float centerX = (this.terrainPackage.width() - 1) / 2.0f;
                float centerZ = (this.terrainPackage.depth() - 1) / 2.0f;
                float radius = metadata.arenaSize / 2.0f + (largePrefab ? 6.0f : 2.0f);
                float chebyshev = Math.max(Math.abs(x - centerX), Math.abs(z - centerZ));
                if (chebyshev <= radius) {
                    return true;
                }
            }
            return false;
        }

        private boolean isNearWaterEdge(int x, int z) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    int nx = x + dx;
                    int nz = z + dz;
                    if (inBounds(nx, nz) && this.terrainPackage.isWaterAt(nx, nz) != this.terrainPackage.isWaterAt(x, z)) {
                        return true;
                    }
                }
            }
            return false;
        }

        private boolean inBounds(int x, int z) {
            return x >= 0 && z >= 0 && x < this.terrainPackage.width() && z < this.terrainPackage.depth();
        }

        private boolean tooCloseToPlaced(@Nonnull List<int[]> points, int x, int z, int spacing) {
            int minSq = spacing * spacing;
            for (int[] point : points) {
                int dx = point[0] - x;
                int dz = point[1] - z;
                if ((dx * dx) + (dz * dz) < minSq) {
                    return true;
                }
            }
            return false;
        }

        private boolean isSlopeSafeCover(@Nonnull String coverId) {
            return coverId.startsWith("Rubble_") || "Soil_Pebbles".equals(coverId);
        }

        private boolean passesDensity(double density, int worldX, int worldZ, int salt) {
            int threshold = (int) Math.round(Math.max(0.0D, Math.min(1.0D, density)) * 10_000.0D);
            return Math.floorMod(hash(worldX, worldZ, salt), 10_000) < threshold;
        }

        private int[] sampleCandidate(int attempt, int salt) {
            int x = Math.floorMod(hash(attempt, salt, 71), this.terrainPackage.width());
            int z = Math.floorMod(hash(salt, attempt, 73), this.terrainPackage.depth());
            return new int[] {x, z};
        }

        private void increment(@Nonnull Map<String, Integer> counts, @Nonnull String key) {
            counts.merge(key, 1, Integer::sum);
        }

        private void logReport() {
            LOGGER.at(Level.INFO).log("Terrain decoration:");
            LOGGER.at(Level.INFO).log("  biome=%s", this.biomeProfile.primary().name().toLowerCase(Locale.ROOT));
            if (this.biomeProfile.hasSnow() || this.biomeProfile.primary() == TerrainBiomeProfile.PrimaryBiome.SNOWY_ALPINE) {
                LOGGER.at(Level.INFO).log("  snowStart=%s snowFull=%s", this.palette.snowStartY(), this.palette.snowFullY());
            }
            LOGGER.at(Level.INFO).log("  cover placed: %s", formatCounts(this.coverCounts));
            LOGGER.at(Level.INFO).log("  trees placed: %s", formatCounts(this.treeCounts));
            LOGGER.at(Level.INFO).log("  natural prefabs: %s", formatCounts(this.naturalCounts));
            LOGGER.at(Level.INFO).log("  skipped protected=%s slope=%s water=%s", this.skippedProtected, this.skippedSlope, this.skippedWater);
        }

        @Nonnull
        private String formatCounts(@Nonnull Map<String, Integer> counts) {
            if (counts.isEmpty()) {
                return "none";
            }
            StringBuilder builder = new StringBuilder();
            boolean first = true;
            for (Map.Entry<String, Integer> entry : counts.entrySet()) {
                if (!first) {
                    builder.append(", ");
                }
                builder.append(entry.getKey()).append('=').append(entry.getValue());
                first = false;
            }
            return builder.toString();
        }

        @Nonnull
        private static String normalize(@Nonnull String value) {
            return value.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        }

        private static int hash(int a, int b, int salt) {
            int h = AiTerrainSettings.SEED_SALT ^ salt;
            h ^= a * 0x1f1f1f1f;
            h ^= b * 0x5f356495;
            h ^= (h >>> 16);
            return h & 0x7fffffff;
        }

        @Nonnull
        private static String pick(int hash, @Nonnull String... options) {
            return options[Math.floorMod(hash, options.length)];
        }

        private record PrefabRule(
            @Nonnull String path,
            double density,
            int minSpacing,
            int maxSlope,
            int footprintRadius,
            boolean requiresWater,
            boolean shoreOnly,
            int minWaterDepth,
            int maxWaterDepth
        ) {
        }
    }
}
