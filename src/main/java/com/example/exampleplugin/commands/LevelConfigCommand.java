package com.example.exampleplugin.commands;

import com.example.exampleplugin.levels.LevelConfigStore;
import com.example.exampleplugin.levels.LevelEditorManager;
import com.example.exampleplugin.levels.model.EnemySpawnDefinition;
import com.example.exampleplugin.levels.model.LevelDefinition;
import com.example.exampleplugin.levels.model.TransformData;
import com.example.exampleplugin.levels.model.Vector3Data;
import com.example.exampleplugin.levels.model.WaveDefinition;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class LevelConfigCommand extends AbstractPlayerCommand {
    private static final String DEFAULT_ENEMY_ROLE = "HyGen_Enemy_Zombie";

    public LevelConfigCommand() {
        super("levelcfg", "Edit level config in-game and save to battleheart-levels.json");
        this.setAllowsExtraArguments(true);
    }

    @Override
    protected void execute(
        @Nonnull CommandContext context,
        @Nonnull Store<EntityStore> store,
        @Nonnull Ref<EntityStore> ref,
        @Nonnull PlayerRef playerRef,
        @Nonnull World world
    ) {
        List<String> args = getPositionalTokens(context);
        String action = args.isEmpty() ? "help" : args.get(0).toLowerCase(Locale.ROOT);
        String arg1 = args.size() > 1 ? args.get(1) : null;
        String arg2 = args.size() > 2 ? args.get(2) : null;
        String tail = joinTail(args, 1);

        switch (action) {
            case "help" -> sendHelp(context);
            case "hud" -> handleHud(context, playerRef, arg1);
            case "select" -> handleSelect(context, playerRef, arg1);
            case "info" -> handleInfo(context, playerRef);
            case "setspawn" -> handleSetSpawn(context, store, ref, playerRef);
            case "setmap" -> handleSetMap(context, playerRef, arg1);
            case "setname" -> handleSetName(context, playerRef, tail);
            case "setwaves" -> handleSetWaves(context, playerRef, arg1);
            case "setwaveenemies" -> handleSetWaveEnemies(context, playerRef, arg1, arg2);
            case "setwaverole" -> handleSetWaveRole(context, playerRef, arg1, joinTail(args, 2));
            case "setwavespawn" -> handleSetWaveSpawn(context, store, ref, playerRef, arg1);
            default -> {
                context.sendMessage(Message.raw("Unknown action '" + action + "'. Use /levelcfg help"));
            }
        }
    }

    private void sendHelp(@Nonnull CommandContext context) {
        context.sendMessage(Message.raw("/levelcfg hud [on|off|toggle]"));
        context.sendMessage(Message.raw("/levelcfg select <levelId>"));
        context.sendMessage(Message.raw("/levelcfg info"));
        context.sendMessage(Message.raw("/levelcfg setspawn"));
        context.sendMessage(Message.raw("/levelcfg setmap <worldName>"));
        context.sendMessage(Message.raw("/levelcfg setname <name>"));
        context.sendMessage(Message.raw("/levelcfg setwaves <count>"));
        context.sendMessage(Message.raw("/levelcfg setwaveenemies <wave> <count>"));
        context.sendMessage(Message.raw("/levelcfg setwaverole <wave> <npcRoleId>"));
        context.sendMessage(Message.raw("/levelcfg setwavespawn <wave>"));
    }

    private void handleHud(@Nonnull CommandContext context, @Nonnull PlayerRef playerRef, @Nullable String mode) {
        if (mode == null || mode.isBlank() || mode.equalsIgnoreCase("toggle")) {
            boolean enabled = LevelEditorManager.get().toggleHud(playerRef.getUuid());
            context.sendMessage(Message.raw("Level editor HUD: " + (enabled ? "ON" : "OFF")));
            return;
        }
        if (mode.equalsIgnoreCase("on")) {
            LevelEditorManager.get().setHudEnabled(playerRef.getUuid(), true);
            context.sendMessage(Message.raw("Level editor HUD: ON"));
            return;
        }
        if (mode.equalsIgnoreCase("off")) {
            LevelEditorManager.get().setHudEnabled(playerRef.getUuid(), false);
            context.sendMessage(Message.raw("Level editor HUD: OFF"));
            return;
        }
        context.sendMessage(Message.raw("Usage: /levelcfg hud [on|off|toggle]"));
    }

    private void handleSelect(@Nonnull CommandContext context, @Nonnull PlayerRef playerRef, @Nullable String levelId) {
        if (levelId == null || levelId.isBlank()) {
            context.sendMessage(Message.raw("Usage: /levelcfg select <levelId>"));
            return;
        }
        LevelDefinition level = LevelConfigStore.get().getLevelById(levelId);
        if (level == null) {
            context.sendMessage(Message.raw("Unknown level id: " + levelId));
            return;
        }
        LevelEditorManager.get().selectLevel(playerRef.getUuid(), level.levelId);
        context.sendMessage(Message.raw("Selected level: " + level.levelId + " (" + safe(level.levelName) + ")"));
    }

    private void handleInfo(@Nonnull CommandContext context, @Nonnull PlayerRef playerRef) {
        LevelDefinition level = selectedLevel(playerRef);
        if (level == null) {
            context.sendMessage(Message.raw("No levels configured."));
            return;
        }
        context.sendMessage(Message.raw("LevelId: " + safe(level.levelId)));
        context.sendMessage(Message.raw("Name: " + safe(level.levelName) + " | Map: " + safe(level.mapWorldName)));
        context.sendMessage(Message.raw("Waves: " + level.waveCount() + " | Boss: " + ((level.bossBattle != null && level.bossBattle) ? "Yes" : "No")));
        for (int i = 0; i < level.waves.size(); i++) {
            WaveDefinition wave = level.waves.get(i);
            EnemySpawnDefinition enemy = firstEnemy(wave);
            context.sendMessage(
                Message.raw(
                    "Wave " + (i + 1) + ": role=" + safe(enemy == null ? null : enemy.npcRoleId)
                        + " count=" + (enemy == null || enemy.count == null ? 0 : enemy.count)
                )
            );
        }
    }

    private void handleSetSpawn(
        @Nonnull CommandContext context,
        @Nonnull Store<EntityStore> store,
        @Nonnull Ref<EntityStore> ref,
        @Nonnull PlayerRef playerRef
    ) {
        LevelDefinition level = selectedLevel(playerRef);
        if (level == null) {
            context.sendMessage(Message.raw("No level selected."));
            return;
        }
        TransformData transform = currentPlayerTransform(store, ref);
        if (transform == null) {
            context.sendMessage(Message.raw("Could not read your transform."));
            return;
        }
        level.playerSpawn = transform;
        LevelConfigStore.get().save();
        context.sendMessage(Message.raw("Set player spawn for " + safe(level.levelId) + "."));
    }

    private void handleSetMap(@Nonnull CommandContext context, @Nonnull PlayerRef playerRef, @Nullable String mapName) {
        if (mapName == null || mapName.isBlank()) {
            context.sendMessage(Message.raw("Usage: /levelcfg setmap <worldName>"));
            return;
        }
        LevelDefinition level = selectedLevel(playerRef);
        if (level == null) {
            context.sendMessage(Message.raw("No level selected."));
            return;
        }
        level.mapWorldName = mapName;
        if (level.mapDisplayName == null || level.mapDisplayName.isBlank()) {
            level.mapDisplayName = mapName;
        }
        LevelConfigStore.get().save();
        context.sendMessage(Message.raw("Set mapWorldName to '" + mapName + "' for " + safe(level.levelId) + "."));
    }

    private void handleSetName(@Nonnull CommandContext context, @Nonnull PlayerRef playerRef, @Nullable String name) {
        if (name == null || name.isBlank()) {
            context.sendMessage(Message.raw("Usage: /levelcfg setname <name>"));
            return;
        }
        LevelDefinition level = selectedLevel(playerRef);
        if (level == null) {
            context.sendMessage(Message.raw("No level selected."));
            return;
        }
        level.levelName = name;
        LevelConfigStore.get().save();
        context.sendMessage(Message.raw("Set level name to '" + name + "'."));
    }

    private void handleSetWaves(@Nonnull CommandContext context, @Nonnull PlayerRef playerRef, @Nullable String countRaw) {
        Integer count = parsePositiveInt(countRaw);
        if (count == null) {
            context.sendMessage(Message.raw("Usage: /levelcfg setwaves <count>"));
            return;
        }
        LevelDefinition level = selectedLevel(playerRef);
        if (level == null) {
            context.sendMessage(Message.raw("No level selected."));
            return;
        }

        while (level.waves.size() < count) {
            level.waves.add(defaultWave(level.waves.size() + 1));
        }
        while (level.waves.size() > count) {
            level.waves.remove(level.waves.size() - 1);
        }
        renumberWaves(level);
        LevelConfigStore.get().save();
        context.sendMessage(Message.raw("Set waves to " + count + " for " + safe(level.levelId) + "."));
    }

    private void handleSetWaveEnemies(
        @Nonnull CommandContext context,
        @Nonnull PlayerRef playerRef,
        @Nullable String waveRaw,
        @Nullable String countRaw
    ) {
        Integer waveIndex = parsePositiveInt(waveRaw);
        Integer count = parsePositiveInt(countRaw);
        if (waveIndex == null || count == null) {
            context.sendMessage(Message.raw("Usage: /levelcfg setwaveenemies <wave> <count>"));
            return;
        }
        LevelDefinition level = selectedLevel(playerRef);
        if (level == null) {
            context.sendMessage(Message.raw("No level selected."));
            return;
        }
        WaveDefinition wave = ensureWave(level, waveIndex);
        EnemySpawnDefinition enemy = ensureFirstEnemy(wave);
        enemy.count = count;
        LevelConfigStore.get().save();
        context.sendMessage(Message.raw("Wave " + waveIndex + " enemy count set to " + count + "."));
    }

    private void handleSetWaveRole(
        @Nonnull CommandContext context,
        @Nonnull PlayerRef playerRef,
        @Nullable String waveRaw,
        @Nullable String roleId
    ) {
        Integer waveIndex = parsePositiveInt(waveRaw);
        if (waveIndex == null || roleId == null || roleId.isBlank()) {
            context.sendMessage(Message.raw("Usage: /levelcfg setwaverole <wave> <npcRoleId>"));
            return;
        }
        LevelDefinition level = selectedLevel(playerRef);
        if (level == null) {
            context.sendMessage(Message.raw("No level selected."));
            return;
        }
        WaveDefinition wave = ensureWave(level, waveIndex);
        EnemySpawnDefinition enemy = ensureFirstEnemy(wave);
        enemy.npcRoleId = roleId;
        LevelConfigStore.get().save();
        context.sendMessage(Message.raw("Wave " + waveIndex + " role set to '" + roleId + "'."));
    }

    private void handleSetWaveSpawn(
        @Nonnull CommandContext context,
        @Nonnull Store<EntityStore> store,
        @Nonnull Ref<EntityStore> ref,
        @Nonnull PlayerRef playerRef,
        @Nullable String waveRaw
    ) {
        Integer waveIndex = parsePositiveInt(waveRaw);
        if (waveIndex == null) {
            context.sendMessage(Message.raw("Usage: /levelcfg setwavespawn <wave>"));
            return;
        }
        LevelDefinition level = selectedLevel(playerRef);
        if (level == null) {
            context.sendMessage(Message.raw("No level selected."));
            return;
        }
        TransformData transform = currentPlayerTransform(store, ref);
        if (transform == null) {
            context.sendMessage(Message.raw("Could not read your transform."));
            return;
        }
        WaveDefinition wave = ensureWave(level, waveIndex);
        EnemySpawnDefinition enemy = ensureFirstEnemy(wave);
        enemy.spawn = transform;
        LevelConfigStore.get().save();
        context.sendMessage(Message.raw("Wave " + waveIndex + " spawn set to your current position."));
    }

    @Nullable
    private static LevelDefinition selectedLevel(@Nonnull PlayerRef playerRef) {
        return LevelEditorManager.get().getSelectedLevel(playerRef);
    }

    @Nullable
    private static Integer parsePositiveInt(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        try {
            int value = Integer.parseInt(raw);
            return value <= 0 ? null : value;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    @Nullable
    private static TransformData currentPlayerTransform(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
        HeadRotation head = store.getComponent(ref, HeadRotation.getComponentType());
        if (transform == null) {
            return null;
        }
        Vector3d p = transform.getPosition();
        Vector3f r = head != null ? head.getRotation() : transform.getRotation();
        return new TransformData(new Vector3Data(p.x, p.y, p.z), r.getYaw(), r.getPitch(), r.getRoll());
    }

    @Nonnull
    private static WaveDefinition defaultWave(int waveNumber) {
        WaveDefinition wave = new WaveDefinition();
        wave.waveNumber = waveNumber;
        wave.bossWave = Boolean.FALSE;
        wave.startDelayMs = 500;
        wave.interWaveDelayMs = 2500;
        wave.enemies.add(defaultEnemy());
        return wave;
    }

    @Nonnull
    private static EnemySpawnDefinition defaultEnemy() {
        EnemySpawnDefinition enemy = new EnemySpawnDefinition();
        enemy.npcRoleId = DEFAULT_ENEMY_ROLE;
        enemy.count = 1;
        enemy.spawn = new TransformData(new Vector3Data(0.0, 0.0, 0.0), 0.0f, 0.0f, 0.0f);
        return enemy;
    }

    @Nonnull
    private static WaveDefinition ensureWave(@Nonnull LevelDefinition level, int waveIndexOneBased) {
        while (level.waves.size() < waveIndexOneBased) {
            level.waves.add(defaultWave(level.waves.size() + 1));
        }
        renumberWaves(level);
        return level.waves.get(waveIndexOneBased - 1);
    }

    private static void renumberWaves(@Nonnull LevelDefinition level) {
        for (int i = 0; i < level.waves.size(); i++) {
            level.waves.get(i).waveNumber = i + 1;
        }
    }

    @Nonnull
    private static EnemySpawnDefinition ensureFirstEnemy(@Nonnull WaveDefinition wave) {
        if (wave.enemies == null) {
            wave.enemies = new java.util.ArrayList<>();
        }
        if (wave.enemies.isEmpty()) {
            wave.enemies.add(defaultEnemy());
        }
        EnemySpawnDefinition enemy = wave.enemies.get(0);
        if (enemy.npcRoleId == null || enemy.npcRoleId.isBlank()) {
            enemy.npcRoleId = DEFAULT_ENEMY_ROLE;
        }
        if (enemy.count == null || enemy.count <= 0) {
            enemy.count = 1;
        }
        if (enemy.spawn == null) {
            enemy.spawn = new TransformData(new Vector3Data(0.0, 0.0, 0.0), 0.0f, 0.0f, 0.0f);
        }
        return enemy;
    }

    @Nullable
    private static EnemySpawnDefinition firstEnemy(@Nullable WaveDefinition wave) {
        if (wave == null || wave.enemies == null || wave.enemies.isEmpty()) {
            return null;
        }
        return wave.enemies.get(0);
    }

    @Nonnull
    private static String safe(@Nullable String value) {
        return value == null ? "?" : value;
    }

    @Nonnull
    private static List<String> getPositionalTokens(@Nonnull CommandContext context) {
        String input = context.getInputString();
        if (input == null || input.isBlank()) {
            return List.of();
        }
        String[] split = input.trim().split("\\s+");
        List<String> tokens = new ArrayList<>();
        for (int i = 1; i < split.length; i++) {
            String token = split[i];
            if (token == null || token.isBlank() || token.startsWith("--")) {
                continue;
            }
            tokens.add(token);
        }
        return tokens;
    }

    @Nullable
    private static String joinTail(@Nonnull List<String> args, int start) {
        if (args.size() <= start) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < args.size(); i++) {
            if (i > start) {
                sb.append(' ');
            }
            sb.append(args.get(i));
        }
        return sb.toString();
    }
}
