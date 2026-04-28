package com.example.exampleplugin.worldgen;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.environment.config.Environment;
import com.hypixel.hytale.server.core.universe.world.worldgen.GeneratedBlockChunk;
import com.hypixel.hytale.server.core.universe.world.worldgen.GeneratedChunk;
import com.hypixel.hytale.server.core.universe.world.worldgen.IWorldGen;
import com.hypixel.hytale.server.core.universe.world.worldgen.WorldGenLoadException;
import com.hypixel.hytale.server.core.universe.world.worldgen.WorldGenTimingsCollector;
import com.hypixel.hytale.server.core.universe.world.worldgen.provider.IWorldGenProvider;

import javax.annotation.Nonnull;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongPredicate;

public final class AiPrefabWorldGenProvider implements IWorldGenProvider {
    public static final String ID = "AIPrefab";
    public static final BuilderCodec<AiPrefabWorldGenProvider> CODEC = BuilderCodec
        .builder(AiPrefabWorldGenProvider.class, AiPrefabWorldGenProvider::new)
        .documentation("Generates terrain by clipping a server prefab JSON into generated chunks over an air/void base.")
        .append(new KeyedCodec<>("ConfigFile", Codec.STRING), (provider, value) -> provider.configFile = value, provider -> provider.configFile)
        .documentation("Universe-relative config file. Defaults to ai-prefab-config.json.")
        .add()
        .build();

    private String configFile = "ai-prefab-config.json";

    @Override
    public IWorldGen getGenerator() throws WorldGenLoadException {
        AiPrefabConfig config = AiPrefabStore.get().ensureConfig(this.configFile);
        AiPrefabVolume volume = AiPrefabStore.get().ensureLoaded(this.configFile);
        int environmentId = environmentId(config.environment);
        int flatBaseBlockId = blockId(config.flatBaseBlock, "Rock_Stone");
        int flatSubSurfaceBlockId = blockId(config.flatSubSurfaceBlock, "Soil_Dirt");
        int flatSurfaceBlockId = blockId(config.flatSurfaceBlock, "Soil_Grass");
        return new Generator(config, volume, environmentId, flatBaseBlockId, flatSubSurfaceBlockId, flatSurfaceBlockId);
    }

    @Override
    public String toString() {
        return ID + "{configFile=" + this.configFile + "}";
    }

    private static int environmentId(@Nonnull String key) throws WorldGenLoadException {
        int id = Environment.getAssetMap().getIndex(key);
        if (id == Integer.MIN_VALUE) {
            throw new WorldGenLoadException("Unknown environment for AI prefab worldgen: " + key);
        }
        return id;
    }

    private static int blockId(@Nonnull String key, @Nonnull String fallbackKey) {
        int id = BlockType.getAssetMap().getIndex(key);
        if (id != Integer.MIN_VALUE) {
            return id;
        }
        return BlockType.getBlockIdOrUnknown(fallbackKey, "Failed to find block key {0}; using fallback " + fallbackKey, key);
    }

    private static final class Generator implements IWorldGen {
        private static final int CHUNK_SIZE = 32;
        private static final int DEFAULT_TINT = 0;

        private final AiPrefabConfig config;
        private final AiPrefabVolume volume;
        private final int environmentId;
        private final int flatBaseBlockId;
        private final int flatSubSurfaceBlockId;
        private final int flatSurfaceBlockId;

        private Generator(
            @Nonnull AiPrefabConfig config,
            @Nonnull AiPrefabVolume volume,
            int environmentId,
            int flatBaseBlockId,
            int flatSubSurfaceBlockId,
            int flatSurfaceBlockId
        ) {
            this.config = config;
            this.volume = volume;
            this.environmentId = environmentId;
            this.flatBaseBlockId = flatBaseBlockId;
            this.flatSubSurfaceBlockId = flatSubSurfaceBlockId;
            this.flatSurfaceBlockId = flatSurfaceBlockId;
        }

        @Override
        public CompletableFuture<GeneratedChunk> generate(
            int lod,
            long chunkIndex,
            int chunkX,
            int chunkZ,
            LongPredicate shouldStillGenerate
        ) {
            return CompletableFuture.supplyAsync(() -> generateNow(chunkIndex, chunkX, chunkZ, shouldStillGenerate));
        }

        private GeneratedChunk generateNow(long chunkIndex, int chunkX, int chunkZ, LongPredicate shouldStillGenerate) {
            if (shouldStillGenerate != null && !shouldStillGenerate.test(chunkIndex)) {
                return null;
            }

            GeneratedChunk generated = new GeneratedChunk();
            GeneratedBlockChunk blocks = generated.getBlockChunk();
            blocks.setCoordinates(chunkIndex, chunkX, chunkZ);

            for (int localX = 0; localX < CHUNK_SIZE; localX++) {
                for (int localZ = 0; localZ < CHUNK_SIZE; localZ++) {
                    blocks.setTint(localX, localZ, DEFAULT_TINT);
                    blocks.setEnvironmentColumn(localX, localZ, this.environmentId);
                }
            }

            if (this.config.flatWorld) {
                fillFlatWorld(blocks);
            }

            if (!this.volume.intersectsChunk(chunkX, chunkZ)) {
                blocks.generateHeight();
                return generated;
            }

            for (AiPrefabVolume.PlacedBlock block : this.volume.blocksForChunk(chunkX, chunkZ)) {
                blocks.setBlock(block.localX(), block.y(), block.localZ(), block.blockId(), block.rotation(), block.filler());
            }

            blocks.generateHeight();
            return generated;
        }

        private void fillFlatWorld(@Nonnull GeneratedBlockChunk blocks) {
            int surfaceY = this.config.flatSurfaceY >= 0 ? this.config.flatSurfaceY : this.config.spawnY - 1;
            surfaceY = Math.max(0, Math.min(319, surfaceY));
            int subSurfaceY = Math.max(0, surfaceY - 1);

            for (int localX = 0; localX < CHUNK_SIZE; localX++) {
                for (int localZ = 0; localZ < CHUNK_SIZE; localZ++) {
                    for (int y = 0; y <= surfaceY; y++) {
                        int blockId = y == surfaceY
                            ? this.flatSurfaceBlockId
                            : (y >= subSurfaceY ? this.flatSubSurfaceBlockId : this.flatBaseBlockId);
                        blocks.setBlock(localX, y, localZ, blockId, 0, 0);
                    }
                }
            }
        }

        @Override
        public Transform[] getSpawnPoints(int count) {
            return new Transform[] {
                new Transform(this.config.spawnX + 0.5D, this.config.spawnY, this.config.spawnZ + 0.5D)
            };
        }

        @Override
        public WorldGenTimingsCollector getTimings() {
            return null;
        }
    }
}
