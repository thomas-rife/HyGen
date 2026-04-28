package com.example.exampleplugin.levels;

import com.example.exampleplugin.levels.model.EnemySpawnDefinition;
import com.example.exampleplugin.levels.model.LevelCatalog;
import com.example.exampleplugin.levels.model.LevelDefinition;
import com.example.exampleplugin.levels.model.TransformData;
import com.example.exampleplugin.levels.model.Vector3Data;
import com.example.exampleplugin.levels.model.WaveDefinition;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.hypixel.hytale.server.core.universe.Universe;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class LevelConfigStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "battleheart-levels.json";
    private static final LevelConfigStore INSTANCE = new LevelConfigStore();

    @Nullable
    private volatile LevelCatalog catalog;

    private LevelConfigStore() {
    }

    @Nonnull
    public static LevelConfigStore get() {
        return INSTANCE;
    }

    @Nonnull
    public synchronized LevelCatalog ensureLoaded() {
        if (this.catalog != null) {
            return this.catalog;
        }

        Path path = getFilePath();
        try {
            if (!Files.exists(path)) {
                LevelCatalog defaults = createDefaultCatalog();
                saveInternal(path, defaults);
                this.catalog = defaults;
                return defaults;
            }

            try (Reader reader = Files.newBufferedReader(path)) {
                LevelCatalog loaded = GSON.fromJson(reader, LevelCatalog.class);
                if (loaded == null || loaded.levels == null || loaded.levels.isEmpty()) {
                    loaded = createDefaultCatalog();
                    saveInternal(path, loaded);
                } else if (normalizeDefaults(loaded)) {
                    saveInternal(path, loaded);
                }
                this.catalog = loaded;
                return loaded;
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to load level catalog from " + path, e);
        }
    }

    public synchronized void save() {
        LevelCatalog current = this.catalog == null ? createDefaultCatalog() : this.catalog;
        try {
            saveInternal(getFilePath(), current);
            this.catalog = current;
        } catch (IOException e) {
            throw new RuntimeException("Failed to save level catalog", e);
        }
    }

    @Nonnull
    public synchronized List<LevelDefinition> getLevels() {
        LevelCatalog loaded = ensureLoaded();
        return new ArrayList<>(loaded.levels);
    }

    @Nullable
    public synchronized LevelDefinition getLevelById(@Nonnull String levelId) {
        for (LevelDefinition level : ensureLoaded().levels) {
            if (level != null && level.levelId != null && level.levelId.equalsIgnoreCase(levelId)) {
                return level;
            }
        }
        return null;
    }

    @Nonnull
    public synchronized Path getFilePath() {
        return Universe.get().getPath().resolve(FILE_NAME);
    }

    @Nonnull
    private static LevelCatalog createDefaultCatalog() {
        LevelCatalog catalog = new LevelCatalog();

        catalog.levels.add(createTutorialLevel());
        catalog.levels.add(createLevel2());
        catalog.levels.add(createVoidEyeBossLevel());
        catalog.levels.add(createLevel4());
        catalog.levels.add(createLevel5());
        catalog.levels.add(createFinalLevel());
        return catalog;
    }

    @Nonnull
    private static LevelDefinition createLevel2() {
        LevelDefinition level = createDefaultLevel(
            "level_2",
            "Level 2",
            "map2",
            "Map 2",
            2,
            true,
            new Vector3Data(938.0, 115.0, 506.0),
            new Vector3Data(946.0, 115.0, 506.0)
        );
        level.waves = createLevel2Waves();
        return level;
    }

    @Nonnull
    private static LevelDefinition createLevel4() {
        LevelDefinition level = createDefaultLevel(
            "level_4",
            "Level 4",
            "map1",
            "Map 1",
            4,
            true,
            new Vector3Data(27.07046127319336, 181.0, -42.63945388793945),
            new Vector3Data(24.811503982543947, 181.0, -10.901930236816407)
        );
        level.playerSpawn = new TransformData(
            new Vector3Data(27.07046127319336, 181.0, -42.63945388793945),
            3.0601387f,
            -0.15209883f,
            0.0f
        );
        level.waves = createLevel4Waves();
        return level;
    }

    @Nonnull
    private static LevelDefinition createLevel5() {
        LevelDefinition level = createDefaultLevel(
            "level_5",
            "Level 5",
            "map2",
            "Map 2",
            5,
            true,
            new Vector3Data(2502.0, 139.0, 466.0),
            new Vector3Data(2512.0, 139.0, 466.0)
        );
        level.waves = createLevel5Waves();
        return level;
    }

    @Nonnull
    private static LevelDefinition createFinalLevel() {
        LevelDefinition level = createDefaultLevel(
            "level_6",
            "Final Level",
            "map2",
            "Map 2",
            6,
            true,
            new Vector3Data(2505.0, 117.0, 469.0),
            new Vector3Data(2513.0, 117.0, 469.0)
        );
        level.waves = createFinalLevelWaves();
        return level;
    }

    private static boolean normalizeDefaults(@Nonnull LevelCatalog catalog) {
        boolean changed = false;
        boolean hasLevel2 = false;
        boolean hasLevel3 = false;
        boolean hasLevel4 = false;
        boolean hasLevel5 = false;
        boolean hasLevel6 = false;
        for (LevelDefinition level : catalog.levels) {
            if (level == null || level.levelId == null) {
                continue;
            }
            if (level.levelId.equalsIgnoreCase("level_2")) {
                hasLevel2 = true;
                if (level.mapWorldName == null || level.mapWorldName.isBlank() || level.mapWorldName.equalsIgnoreCase("levels")) {
                    level.mapWorldName = "map2";
                    changed = true;
                }
                if (!Boolean.TRUE.equals(level.overviewCamera)) {
                    level.overviewCamera = Boolean.TRUE;
                    changed = true;
                }
                if (level.waves == null || level.waves.isEmpty() || hasOnlyDefaultZombieWave(level)) {
                    level.waves = createLevel2Waves();
                    changed = true;
                }
            }
            if (level.levelId.equalsIgnoreCase("level_3")) {
                hasLevel3 = true;
                if (level.mapWorldName == null || level.mapWorldName.isBlank() || level.mapWorldName.equalsIgnoreCase("levels")) {
                    level.mapWorldName = "map2";
                    changed = true;
                }
                if (!Boolean.TRUE.equals(level.overviewCamera)) {
                    level.overviewCamera = Boolean.TRUE;
                    changed = true;
                }
                if (!Boolean.TRUE.equals(level.bossBattle)) {
                    level.bossBattle = Boolean.TRUE;
                    changed = true;
                }
                if (level.waves == null || level.waves.isEmpty() || hasOnlyDefaultZombieWave(level)) {
                    level.waves = createVoidEyeBossWaves();
                    changed = true;
                }
            }
            if (level.levelId.equalsIgnoreCase("level_4")) {
                hasLevel4 = true;
                if (!"map1".equalsIgnoreCase(level.mapWorldName)) {
                    level.mapWorldName = "map1";
                    changed = true;
                }
                if (!"Map 1".equals(level.mapDisplayName)) {
                    level.mapDisplayName = "Map 1";
                    changed = true;
                }
                if (!Boolean.TRUE.equals(level.overviewCamera)) {
                    level.overviewCamera = Boolean.TRUE;
                    changed = true;
                }
                if (level.waves == null || level.waves.isEmpty() || hasOnlyDefaultZombieWave(level)) {
                    level.waves = createLevel4Waves();
                    changed = true;
                }
            }
            if (level.levelId.equalsIgnoreCase("level_5")) {
                hasLevel5 = true;
                if (level.mapWorldName == null || level.mapWorldName.isBlank() || level.mapWorldName.equalsIgnoreCase("levels")) {
                    level.mapWorldName = "map2";
                    changed = true;
                }
                if (!Boolean.TRUE.equals(level.overviewCamera)) {
                    level.overviewCamera = Boolean.TRUE;
                    changed = true;
                }
                if (level.waves == null || level.waves.isEmpty() || hasOnlyDefaultZombieWave(level)) {
                    level.waves = createLevel5Waves();
                    changed = true;
                }
            }
            if (level.levelId.equalsIgnoreCase("level_6")) {
                hasLevel6 = true;
                if (level.mapWorldName == null || level.mapWorldName.isBlank() || level.mapWorldName.equalsIgnoreCase("levels")) {
                    level.mapWorldName = "map2";
                    changed = true;
                }
                if (!Boolean.TRUE.equals(level.overviewCamera)) {
                    level.overviewCamera = Boolean.TRUE;
                    changed = true;
                }
                if (level.waves == null || level.waves.isEmpty() || hasOnlyDefaultZombieWave(level) || hasPreviousFinalLevelWaves(level)) {
                    level.waves = createFinalLevelWaves();
                    changed = true;
                }
            }
        }
        if (!hasLevel2) {
            catalog.levels.add(createLevel2());
            changed = true;
        }
        if (!hasLevel3) {
            catalog.levels.add(createVoidEyeBossLevel());
            changed = true;
        }
        if (!hasLevel4) {
            catalog.levels.add(createLevel4());
            changed = true;
        }
        if (!hasLevel5) {
            catalog.levels.add(createLevel5());
            changed = true;
        }
        if (!hasLevel6) {
            catalog.levels.add(createFinalLevel());
            changed = true;
        }
        return changed;
    }

    @Nonnull
    private static LevelDefinition createDefaultLevel(
        @Nonnull String levelId,
        @Nonnull String levelName,
        @Nonnull String mapWorldName,
        @Nonnull String mapDisplayName,
        int orderIndex,
        boolean overviewCamera,
        @Nonnull Vector3Data playerSpawn,
        @Nonnull Vector3Data enemySpawn
    ) {
        LevelDefinition level = new LevelDefinition();
        level.levelId = levelId;
        level.levelName = levelName;
        level.mapWorldName = mapWorldName;
        level.mapDisplayName = mapDisplayName;
        level.overviewCamera = overviewCamera;
        level.orderIndex = orderIndex;
        level.bossBattle = Boolean.FALSE;
        level.maxPartySize = 1;
        level.recommendedPower = 1;
        level.playerSpawn = new TransformData(playerSpawn, 0.0f, 0.0f, 0.0f);
        level.fallbackReturnSpawn = null;
        level.enemySpawnLocations.add(enemySpawn);

        WaveDefinition wave1 = new WaveDefinition();
        wave1.waveNumber = 1;
        wave1.bossWave = Boolean.FALSE;
        wave1.startDelayMs = 500;
        wave1.interWaveDelayMs = 2500;

        EnemySpawnDefinition enemy = new EnemySpawnDefinition();
        enemy.npcRoleId = "HyGen_Enemy_Zombie";
        enemy.count = 10;
        enemy.spawn = new TransformData(enemySpawn, 0.0f, 0.0f, 0.0f);
        wave1.enemies.add(enemy);
        level.waves.add(wave1);
        return level;
    }

    @Nonnull
    private static LevelDefinition createTutorialLevel() {
        LevelDefinition level = new LevelDefinition();
        level.levelId = "level_1";
        level.levelName = "Level 1";
        level.mapWorldName = "map1";
        level.mapDisplayName = "Map 1";
        level.orderIndex = 1;
        level.bossBattle = Boolean.FALSE;
        level.maxPartySize = 4;
        level.recommendedPower = 1;
        level.playerSpawn = new TransformData(
            new Vector3Data(27.07046127319336, 181.0, -42.63945388793945),
            3.0601387f,
            -0.15209883f,
            0.0f
        );
        level.fallbackReturnSpawn = null;
        level.enemySpawnLocations.add(new Vector3Data(156.0, 122.0, 136.0));
        level.waves.add(createWave(
            1,
            2500,
            createEnemy("HyGen_Enemy_Zombie", 8, new Vector3Data(24.811503982543947, 181.0, -10.901930236816407), 0.0039686887f, -0.4508982f)
        ));
        level.waves.add(createWave(
            2,
            3000,
            createEnemy("HyGen_Enemy_Zombie", 8, new Vector3Data(24.211503982543945, 181.0, -11.701930236816406), 0.0039686887f, -0.4508982f),
            createEnemy("HyGen_Enemy_Archer", 4, new Vector3Data(30.411503982543945, 181.0, -14.901930236816407), 0.0039686887f, -0.4508982f)
        ));
        level.waves.add(createWave(
            3,
            2500,
            createEnemy("HyGen_Enemy_Zombie", 16, new Vector3Data(24.811503982543947, 181.0, -10.901930236816407), 0.0039686887f, -0.4508982f)
        ));
        return level;
    }

    @Nonnull
    private static WaveDefinition createWave(int waveNumber, int interWaveDelayMs, EnemySpawnDefinition... enemies) {
        WaveDefinition wave = new WaveDefinition();
        wave.waveNumber = waveNumber;
        wave.bossWave = Boolean.FALSE;
        wave.startDelayMs = 500;
        wave.interWaveDelayMs = interWaveDelayMs;
        for (EnemySpawnDefinition enemy : enemies) {
            wave.enemies.add(enemy);
        }
        return wave;
    }

    @Nonnull
    private static EnemySpawnDefinition createEnemy(
        @Nonnull String npcRoleId,
        int count,
        @Nonnull Vector3Data spawnPosition,
        float yaw,
        float pitch
    ) {
        EnemySpawnDefinition enemy = new EnemySpawnDefinition();
        enemy.npcRoleId = npcRoleId;
        enemy.count = count;
        enemy.spawn = new TransformData(spawnPosition, yaw, pitch, 0.0f);
        return enemy;
    }

    @Nonnull
    private static LevelDefinition createVoidEyeBossLevel() {
        LevelDefinition level = createDefaultLevel(
            "level_3",
            "Level 3",
            "map2",
            "Map 2",
            3,
            true,
            new Vector3Data(1691.0, 130.0, 2783.0),
            new Vector3Data(1699.0, 130.0, 2783.0)
        );
        level.bossBattle = Boolean.TRUE;
        level.waves = createVoidEyeBossWaves();
        return level;
    }

    @Nonnull
    private static List<WaveDefinition> createVoidEyeBossWaves() {
        List<WaveDefinition> waves = new ArrayList<>();
        WaveDefinition bossWave = createWave(
            1,
            2500,
            createEnemy("Eye_Void", 1, new Vector3Data(1699.0, 130.0, 2783.0), 0.0f, 0.0f)
        );
        bossWave.bossWave = Boolean.TRUE;
        waves.add(bossWave);
        return waves;
    }

    @Nonnull
    private static List<WaveDefinition> createLevel2Waves() {
        List<WaveDefinition> waves = new ArrayList<>();
        waves.add(createWave(
            1,
            2500,
            createEnemy("HyGen_Enemy_Zombie", 10, new Vector3Data(942.0, 115.0, 506.0), 0.0f, 0.0f)
        ));
        waves.add(createWave(
            2,
            3000,
            createEnemy("HyGen_Enemy_Zombie", 8, new Vector3Data(942.0, 115.0, 506.0), 0.0f, 0.0f),
            createEnemy("HyGen_Enemy_Archer", 4, new Vector3Data(944.0, 115.0, 508.0), 0.0f, 0.0f)
        ));
        waves.add(createWave(
            3,
            3000,
            createEnemy("HyGen_Enemy_Zombie", 8, new Vector3Data(942.0, 115.0, 506.0), 0.0f, 0.0f),
            createEnemy("HyGen_Enemy_Diver", 4, new Vector3Data(940.0, 115.0, 510.0), 0.0f, 0.0f)
        ));
        return waves;
    }

    @Nonnull
    private static List<WaveDefinition> createLevel4Waves() {
        List<WaveDefinition> waves = new ArrayList<>();
        waves.add(createWave(
            1,
            3000,
            createEnemy("HyGen_Enemy_Zombie", 10, new Vector3Data(24.811503982543947, 181.0, -10.901930236816407), 0.0039686887f, -0.4508982f),
            createEnemy("HyGen_Enemy_Archer", 4, new Vector3Data(30.411503982543945, 181.0, -14.901930236816407), 0.0039686887f, -0.4508982f)
        ));
        waves.add(createWave(
            2,
            3000,
            createEnemy("HyGen_Enemy_Diver", 8, new Vector3Data(21.811503982543947, 181.0, -13.901930236816407), 0.0039686887f, -0.4508982f),
            createEnemy("HyGen_Enemy_Zombie", 8, new Vector3Data(27.811503982543947, 181.0, -10.901930236816407), 0.0039686887f, -0.4508982f)
        ));
        waves.add(createWave(
            3,
            3500,
            createEnemy("HyGen_Enemy_Zombie", 12, new Vector3Data(24.811503982543947, 181.0, -10.901930236816407), 0.0039686887f, -0.4508982f),
            createEnemy("HyGen_Enemy_Archer", 6, new Vector3Data(30.411503982543945, 181.0, -14.901930236816407), 0.0039686887f, -0.4508982f),
            createEnemy("HyGen_Enemy_Diver", 4, new Vector3Data(21.811503982543947, 181.0, -13.901930236816407), 0.0039686887f, -0.4508982f)
        ));
        return waves;
    }

    @Nonnull
    private static List<WaveDefinition> createLevel5Waves() {
        List<WaveDefinition> waves = new ArrayList<>();
        waves.add(createWave(
            1,
            3000,
            createEnemy("HyGen_Enemy_Diver", 8, new Vector3Data(2512.0, 139.0, 464.0), 0.0f, 0.0f),
            createEnemy("HyGen_Enemy_Zombie", 6, new Vector3Data(2512.0, 139.0, 468.0), 0.0f, 0.0f)
        ));
        waves.add(createWave(
            2,
            3000,
            createEnemy("HyGen_Enemy_Zombie", 10, new Vector3Data(2512.0, 139.0, 464.0), 0.0f, 0.0f),
            createEnemy("HyGen_Enemy_Archer", 6, new Vector3Data(2512.0, 139.0, 468.0), 0.0f, 0.0f)
        ));
        waves.add(createWave(
            3,
            3500,
            createEnemy("HyGen_Enemy_Diver", 10, new Vector3Data(2512.0, 139.0, 464.0), 0.0f, 0.0f),
            createEnemy("HyGen_Enemy_Zombie", 8, new Vector3Data(2512.0, 139.0, 468.0), 0.0f, 0.0f),
            createEnemy("HyGen_Enemy_Archer", 4, new Vector3Data(2512.0, 139.0, 472.0), 0.0f, 0.0f)
        ));
        return waves;
    }

    @Nonnull
    private static List<WaveDefinition> createFinalLevelWaves() {
        List<WaveDefinition> waves = new ArrayList<>();
        waves.add(createWave(
            1,
            3000,
            createEnemy("HyGen_Enemy_Zombie", 10, new Vector3Data(2509.0, 117.0, 469.0), 0.0f, 0.0f),
            createEnemy("HyGen_Enemy_Archer", 3, new Vector3Data(2511.0, 117.0, 471.0), 0.0f, 0.0f)
        ));
        waves.add(createWave(
            2,
            3500,
            createEnemy("HyGen_Enemy_Diver", 10, new Vector3Data(2507.0, 117.0, 473.0), 0.0f, 0.0f),
            createEnemy("HyGen_Enemy_Zombie", 8, new Vector3Data(2510.0, 117.0, 467.0), 0.0f, 0.0f),
            createEnemy("HyGen_Enemy_Archer", 3, new Vector3Data(2511.0, 117.0, 471.0), 0.0f, 0.0f)
        ));
        waves.add(createWave(
            3,
            3500,
            createEnemy("HyGen_Enemy_Zombie", 10, new Vector3Data(2509.0, 117.0, 469.0), 0.0f, 0.0f),
            createEnemy("HyGen_Enemy_Archer", 3, new Vector3Data(2511.0, 117.0, 471.0), 0.0f, 0.0f),
            createEnemy("HyGen_Enemy_Diver", 6, new Vector3Data(2507.0, 117.0, 473.0), 0.0f, 0.0f)
        ));
        waves.add(createWave(
            4,
            4000,
            createEnemy("HyGen_Enemy_Zombie", 11, new Vector3Data(2509.0, 117.0, 469.0), 0.0f, 0.0f),
            createEnemy("HyGen_Enemy_Archer", 3, new Vector3Data(2511.0, 117.0, 471.0), 0.0f, 0.0f),
            createEnemy("HyGen_Enemy_Diver", 8, new Vector3Data(2507.0, 117.0, 473.0), 0.0f, 0.0f)
        ));
        return waves;
    }

    private static boolean hasPreviousFinalLevelWaves(@Nonnull LevelDefinition level) {
        if (level.waves == null || level.waves.size() != 4) {
            return false;
        }
        WaveDefinition finalWave = level.waves.get(3);
        if (finalWave == null || finalWave.enemies == null || finalWave.enemies.isEmpty()) {
            return false;
        }
        EnemySpawnDefinition firstEnemy = finalWave.enemies.get(0);
        return firstEnemy != null
            && "HyGen_Enemy_Zombie".equals(firstEnemy.npcRoleId)
            && firstEnemy.count != null
            && firstEnemy.count == 18;
    }

    private static boolean hasOnlyDefaultZombieWave(@Nonnull LevelDefinition level) {
        if (level.waves == null || level.waves.size() != 1) {
            return false;
        }
        WaveDefinition wave = level.waves.get(0);
        if (wave == null || wave.enemies == null || wave.enemies.size() != 1) {
            return false;
        }
        EnemySpawnDefinition enemy = wave.enemies.get(0);
        return enemy != null
            && "HyGen_Enemy_Zombie".equals(enemy.npcRoleId)
            && enemy.count != null
            && enemy.count == 10;
    }

    private static void saveInternal(@Nonnull Path path, @Nonnull LevelCatalog catalog) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (Writer writer = Files.newBufferedWriter(path)) {
            GSON.toJson(catalog, writer);
        }
    }
}
