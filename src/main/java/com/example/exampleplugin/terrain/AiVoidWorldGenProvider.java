package com.example.exampleplugin.terrain;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.math.vector.Transform;
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

public final class AiVoidWorldGenProvider implements IWorldGenProvider {
    public static final String ID = "AIVoid";
    public static final BuilderCodec<AiVoidWorldGenProvider> CODEC = BuilderCodec
        .builder(AiVoidWorldGenProvider.class, AiVoidWorldGenProvider::new)
        .documentation("Generates an empty void world for AI terrain placement.")
        .build();
    private static final String DEFAULT_ENVIRONMENT = "Default";

    @Override
    public IWorldGen getGenerator() throws WorldGenLoadException {
        return new Generator(environmentId(DEFAULT_ENVIRONMENT));
    }

    private static int environmentId(@Nonnull String key) throws WorldGenLoadException {
        int id = Environment.getAssetMap().getIndex(key);
        if (id == Integer.MIN_VALUE) {
            throw new WorldGenLoadException("Unknown environment for AI void worldgen: " + key);
        }
        return id;
    }

    private static final class Generator implements IWorldGen {
        private static final int CHUNK_SIZE = 32;
        private static final int DEFAULT_TINT = 0xFFFFFF;
        private final int environmentId;

        private Generator(int environmentId) {
            this.environmentId = environmentId;
        }

        @Override
        public CompletableFuture<GeneratedChunk> generate(int lod, long chunkIndex, int chunkX, int chunkZ, LongPredicate shouldStillGenerate) {
            return CompletableFuture.supplyAsync(() -> {
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
                blocks.generateHeight();
                return generated;
            });
        }

        @Override
        public Transform[] getSpawnPoints(int count) {
            return new Transform[] { new Transform(0.5D, 128.0D, 0.5D) };
        }

        @Override
        public WorldGenTimingsCollector getTimings() {
            return null;
        }
    }
}
