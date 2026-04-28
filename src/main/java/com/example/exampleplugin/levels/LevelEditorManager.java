package com.example.exampleplugin.levels;

import com.example.exampleplugin.levels.model.LevelDefinition;
import com.example.exampleplugin.levels.model.WaveDefinition;
import com.example.exampleplugin.levels.model.EnemySpawnDefinition;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class LevelEditorManager {
    private static final LevelEditorManager INSTANCE = new LevelEditorManager();

    private final ConcurrentHashMap<UUID, Boolean> hudEnabled = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, String> selectedLevelId = new ConcurrentHashMap<>();

    private LevelEditorManager() {
    }

    @Nonnull
    public static LevelEditorManager get() {
        return INSTANCE;
    }

    public boolean isHudEnabled(@Nonnull UUID playerId) {
        return this.hudEnabled.getOrDefault(playerId, Boolean.FALSE);
    }

    public void setHudEnabled(@Nonnull UUID playerId, boolean enabled) {
        this.hudEnabled.put(playerId, enabled);
    }

    public boolean toggleHud(@Nonnull UUID playerId) {
        boolean newValue = !isHudEnabled(playerId);
        setHudEnabled(playerId, newValue);
        return newValue;
    }

    public void selectLevel(@Nonnull UUID playerId, @Nonnull String levelId) {
        this.selectedLevelId.put(playerId, levelId);
    }

    @Nullable
    public String getSelectedLevelId(@Nonnull UUID playerId) {
        return this.selectedLevelId.get(playerId);
    }

    @Nullable
    public LevelDefinition getSelectedLevel(@Nonnull PlayerRef playerRef) {
        String selected = getSelectedLevelId(playerRef.getUuid());
        if (selected != null) {
            LevelDefinition byId = LevelConfigStore.get().getLevelById(selected);
            if (byId != null) {
                return byId;
            }
        }

        UUID worldUuid = playerRef.getWorldUuid();
        if (worldUuid != null) {
            World world = Universe.get().getWorld(worldUuid);
            if (world != null) {
                String worldName = world.getName();
                for (LevelDefinition level : LevelConfigStore.get().getLevels()) {
                    if (level != null && level.mapWorldName != null && level.mapWorldName.equalsIgnoreCase(worldName)) {
                        this.selectedLevelId.put(playerRef.getUuid(), level.levelId);
                        return level;
                    }
                }
            }
        }

        List<LevelDefinition> levels = LevelConfigStore.get().getLevels();
        if (levels.isEmpty()) {
            return null;
        }
        LevelDefinition first = levels.get(0);
        if (first != null && first.levelId != null) {
            this.selectedLevelId.put(playerRef.getUuid(), first.levelId);
        }
        return first;
    }

    @Nonnull
    public List<String> buildLeftHudLines(@Nonnull PlayerRef playerRef) {
        List<String> lines = new ArrayList<>();
        LevelDefinition level = getSelectedLevel(playerRef);

        lines.add("[Level Editor]");
        if (level == null) {
            lines.add("No level selected.");
            lines.add("Use: /levelcfg select <levelId>");
            return lines;
        }

        lines.add("Commands:");
        lines.add("/levelcfg info");
        lines.add("/levelcfg setspawn");
        lines.add("/levelcfg setwaves <count>");
        lines.add("/levelcfg setwaveenemies <wave> <count>");
        lines.add("/levelcfg setwaverole <wave> <role>");
        lines.add("/levelcfg setwavespawn <wave>");
        lines.add("/levelcfg setmap <world>");
        lines.add("/levelcfg setname <name>");
        lines.add("/levelcfg hud toggle");
        return lines;
    }

    @Nonnull
    public List<String> buildRightHudLines(@Nonnull PlayerRef playerRef) {
        List<String> lines = new ArrayList<>();
        LevelDefinition level = getSelectedLevel(playerRef);
        lines.add("[Level Breakdown]");
        if (level == null) {
            lines.add("No level selected.");
            return lines;
        }

        lines.add("Id: " + safe(level.levelId, "?"));
        lines.add("Name: " + safe(level.levelName, "?"));
        lines.add("Map: " + safe(level.mapWorldName, "?"));
        lines.add("Boss: " + ((level.bossBattle != null && level.bossBattle) ? "Yes" : "No"));
        lines.add("Waves: " + level.waveCount());
        if (level.playerSpawn != null && level.playerSpawn.position != null) {
            lines.add(
                "Spawn: " + fmt(level.playerSpawn.position.x) + ","
                    + fmt(level.playerSpawn.position.y) + ","
                    + fmt(level.playerSpawn.position.z)
            );
        } else {
            lines.add("Spawn: <unset>");
        }

        int max = Math.min(level.waves == null ? 0 : level.waves.size(), 6);
        for (int i = 0; i < max; i++) {
            WaveDefinition wave = level.waves.get(i);
            EnemySpawnDefinition enemy = firstEnemy(wave);
            String role = safe(enemy == null ? null : enemy.npcRoleId, "Zombie");
            int count = enemy == null || enemy.count == null ? 0 : enemy.count;
            lines.add("Wave " + (i + 1) + ": " + role + " x" + count);
        }
        return lines;
    }

    private static EnemySpawnDefinition firstEnemy(WaveDefinition wave) {
        if (wave == null || wave.enemies == null || wave.enemies.isEmpty()) {
            return null;
        }
        return wave.enemies.get(0);
    }

    private static String fmt(double value) {
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }

    @Nonnull
    private static String safe(@Nullable String value, @Nonnull String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
