package com.example.exampleplugin.worldgen;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockMigration;
import com.hypixel.hytale.server.core.universe.Universe;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

public final class AiPrefabStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String CONFIG_FILE = "ai-prefab-config.json";
    private static final AiPrefabStore INSTANCE = new AiPrefabStore();

    private volatile AiPrefabConfig config;
    private volatile AiPrefabVolume volume;
    private volatile String loadedConfigFile;

    private AiPrefabStore() {
    }

    @Nonnull
    public static AiPrefabStore get() {
        return INSTANCE;
    }

    @Nonnull
    public synchronized AiPrefabVolume ensureLoaded() {
        return ensureLoaded(CONFIG_FILE);
    }

    @Nonnull
    public synchronized AiPrefabVolume ensureLoaded(@Nonnull String configFile) {
        switchConfigFileIfNeeded(configFile);
        if (this.volume != null) {
            return this.volume;
        }

        AiPrefabConfig loadedConfig = ensureConfig(configFile);
        Path prefabPath = resolveUniversePath(loadedConfig.prefabFile);
        if (!Files.exists(prefabPath)) {
            throw new IllegalStateException("Missing AI prefab file: " + prefabPath);
        }

        this.volume = loadPrefab(prefabPath, loadedConfig);
        return this.volume;
    }

    @Nonnull
    public synchronized AiPrefabConfig ensureConfig() {
        return ensureConfig(CONFIG_FILE);
    }

    @Nonnull
    public synchronized AiPrefabConfig ensureConfig(@Nonnull String configFile) {
        switchConfigFileIfNeeded(configFile);
        if (this.config != null) {
            return this.config;
        }

        Path configPath = resolveUniversePath(configFile);
        try {
            if (!Files.exists(configPath)) {
                AiPrefabConfig defaults = new AiPrefabConfig();
                writeConfig(configPath, defaults);
                this.config = defaults;
                return defaults;
            }

            try (Reader reader = Files.newBufferedReader(configPath)) {
                AiPrefabConfig loaded = GSON.fromJson(reader, AiPrefabConfig.class);
                this.config = loaded == null ? new AiPrefabConfig() : loaded;
                return this.config;
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to load AI prefab config from " + configPath, e);
        }
    }

    public synchronized void reload() {
        this.config = null;
        this.volume = null;
    }

    public synchronized void saveConfig(@Nonnull AiPrefabConfig config) {
        String configFile = this.loadedConfigFile == null ? CONFIG_FILE : this.loadedConfigFile;
        try {
            writeConfig(resolveUniversePath(configFile), config);
            this.config = config;
            this.volume = null;
        } catch (IOException e) {
            throw new RuntimeException("Failed to save AI prefab config", e);
        }
    }

    @Nonnull
    private static Path resolveUniversePath(@Nonnull String fileName) {
        return Universe.get().getPath().resolve(fileName).normalize();
    }

    private void switchConfigFileIfNeeded(@Nonnull String configFile) {
        if (this.loadedConfigFile == null || !this.loadedConfigFile.equals(configFile)) {
            this.loadedConfigFile = configFile;
            this.config = null;
            this.volume = null;
        }
    }

    private static void writeConfig(@Nonnull Path path, @Nonnull AiPrefabConfig config) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (Writer writer = Files.newBufferedWriter(path)) {
            GSON.toJson(config, writer);
        }
    }

    @Nonnull
    private static AiPrefabVolume loadPrefab(@Nonnull Path path, @Nonnull AiPrefabConfig config) {
        try (Reader fileReader = Files.newBufferedReader(path);
             JsonReader reader = new JsonReader(fileReader)) {
            return readPrefabVolume(reader, config, path);
        } catch (IOException e) {
            throw new RuntimeException("Failed to stream AI prefab JSON from " + path, e);
        }
    }

    @Nonnull
    private static AiPrefabVolume readPrefabVolume(
        @Nonnull JsonReader reader,
        @Nonnull AiPrefabConfig config,
        @Nonnull Path path
    ) throws IOException {
        AiPrefabVolume volume = new AiPrefabVolume();
        PrefabHeader header = new PrefabHeader();
        Map<String, Integer> blockIdCache = new HashMap<>();

        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            if ("version".equals(name)) {
                header.version = reader.nextInt();
            } else if ("blockIdVersion".equals(name)) {
                header.blockIdVersion = reader.nextInt();
                header.blockMigration = buildBlockMigration(header.blockIdVersion);
            } else if ("anchorX".equals(name)) {
                header.anchorX = reader.nextInt();
            } else if ("anchorY".equals(name)) {
                header.anchorY = reader.nextInt();
            } else if ("anchorZ".equals(name)) {
                header.anchorZ = reader.nextInt();
            } else if ("blocks".equals(name)) {
                readBlocks(reader, config, header, volume, blockIdCache);
            } else {
                reader.skipValue();
            }
        }
        reader.endObject();

        if (volume.minX() == Integer.MAX_VALUE && volume.maxXExclusive() == Integer.MIN_VALUE) {
            throw new IllegalStateException("AI prefab had no readable blocks: " + path);
        }
        return volume;
    }

    private static void readBlocks(
        @Nonnull JsonReader reader,
        @Nonnull AiPrefabConfig config,
        @Nonnull PrefabHeader header,
        @Nonnull AiPrefabVolume volume,
        @Nonnull Map<String, Integer> blockIdCache
    ) throws IOException {
        reader.beginArray();
        while (reader.hasNext()) {
            readBlock(reader, config, header, volume, blockIdCache);
        }
        reader.endArray();
    }

    private static void readBlock(
        @Nonnull JsonReader reader,
        @Nonnull AiPrefabConfig config,
        @Nonnull PrefabHeader header,
        @Nonnull AiPrefabVolume volume,
        @Nonnull Map<String, Integer> blockIdCache
    ) throws IOException {
        int x = 0;
        int y = 0;
        int z = 0;
        int filler = 0;
        int rotation = 0;
        String blockName = null;

        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            switch (name) {
                case "x" -> x = reader.nextInt();
                case "y" -> y = reader.nextInt();
                case "z" -> z = reader.nextInt();
                case "name" -> {
                    if (reader.peek() == JsonToken.NULL) {
                        reader.nextNull();
                    } else {
                        blockName = reader.nextString();
                    }
                }
                case "filler" -> filler = reader.nextInt();
                case "rotation" -> rotation = reader.nextInt();
                default -> reader.skipValue();
            }
        }
        reader.endObject();

        if (blockName == null || blockName.isBlank()) {
            return;
        }

        String blockKey = resolveBlockKey(blockName, config, header);
        int blockId = blockIdCache.computeIfAbsent(
            blockKey,
            key -> BlockType.getBlockIdOrUnknown(key, "Failed to find block '%s' in AI prefab!", key)
        );

        int worldX = config.originX + x - header.anchorX;
        int worldY = config.originY + y - header.anchorY;
        int worldZ = config.originZ + z - header.anchorZ;
        volume.addWorldBlock(worldX, worldY, worldZ, blockId, rotation, filler);
    }

    @Nonnull
    private static String resolveBlockKey(
        @Nonnull String blockName,
        @Nonnull AiPrefabConfig config,
        @Nonnull PrefabHeader header
    ) {
        String blockKey = blockName;
        int metadataStart = blockKey.indexOf('|');
        if (metadataStart >= 0) {
            blockKey = blockKey.substring(0, metadataStart);
        }
        if (header.blockMigration != null) {
            blockKey = header.blockMigration.apply(blockKey);
        }
        if ("Block_Snow".equals(blockKey) && BlockType.getAssetMap().getIndex(blockKey) == Integer.MIN_VALUE) {
            return config.snowBlockFallback;
        }
        return blockKey;
    }

    private static Function<String, String> buildBlockMigration(int blockIdVersion) {
        Function<String, String> blockMigration = null;
        Map<Integer, BlockMigration> blockMigrationMap = BlockMigration.getAssetMap().getAssetMap();
        int version = blockIdVersion;

        for (BlockMigration migration = blockMigrationMap.get(blockIdVersion); migration != null; migration = blockMigrationMap.get(++version)) {
            blockMigration = blockMigration == null ? migration::getMigration : blockMigration.andThen(migration::getMigration);
        }

        return blockMigration;
    }

    private static final class PrefabHeader {
        private int version = 8;
        private int blockIdVersion = 0;
        private int anchorX;
        private int anchorY;
        private int anchorZ;
        private Function<String, String> blockMigration;
    }
}
