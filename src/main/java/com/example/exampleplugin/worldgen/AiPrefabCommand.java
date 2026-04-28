package com.example.exampleplugin.worldgen;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.WorldConfig;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class AiPrefabCommand extends AbstractPlayerCommand {
    private static final String DEFAULT_WORLD_NAME = "ai-prefab-world";

    public AiPrefabCommand() {
        super("aiprefab", "Create and enter an AI prefab-generated world");
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

        switch (action) {
            case "help" -> sendHelp(context);
            case "create" -> createAndEnter(context, playerRef, arg(args, 1), arg(args, 2));
            case "enter" -> enterExisting(context, playerRef, arg(args, 1));
            case "config" -> showConfig(context);
            case "setprefab" -> setPrefab(context, arg(args, 1));
            default -> context.sendMessage(Message.raw("Unknown action '" + action + "'. Use /aiprefab help"));
        }
    }

    private static void sendHelp(@Nonnull CommandContext context) {
        context.sendMessage(Message.raw("/aiprefab create [prefabNameOrWorldName] [prefabFile]"));
        context.sendMessage(Message.raw("/aiprefab enter [worldName]"));
        context.sendMessage(Message.raw("/aiprefab setprefab <prefabFile>"));
        context.sendMessage(Message.raw("/aiprefab config"));
    }

    private static void showConfig(@Nonnull CommandContext context) {
        AiPrefabConfig config = AiPrefabStore.get().ensureConfig();
        context.sendMessage(Message.raw("Prefab: " + config.prefabFile));
        context.sendMessage(Message.raw("Origin: " + config.originX + ", " + config.originY + ", " + config.originZ));
        context.sendMessage(Message.raw("Spawn: " + config.spawnX + ", " + config.spawnY + ", " + config.spawnZ));
        context.sendMessage(Message.raw("Flat: " + config.flatWorld + " surfaceY=" + effectiveFlatSurfaceY(config)));
    }

    private static void setPrefab(@Nonnull CommandContext context, @Nullable String prefabFile) {
        if (prefabFile == null || prefabFile.isBlank()) {
            context.sendMessage(Message.raw("Usage: /aiprefab setprefab <prefabFile>"));
            return;
        }
        AiPrefabConfig config = AiPrefabStore.get().ensureConfig();
        config.prefabFile = normalizePrefabFile(prefabFile);
        AiPrefabStore.get().saveConfig(config);
        AiPrefabStore.get().reload();
        context.sendMessage(Message.raw("AI prefab set to " + config.prefabFile));
    }

    private static void createAndEnter(
        @Nonnull CommandContext context,
        @Nonnull PlayerRef playerRef,
        @Nullable String worldName,
        @Nullable String prefabFile
    ) {
        CreateTarget target = resolveCreateTarget(worldName, prefabFile);
        String targetWorldName = target.worldName();
        AiPrefabConfig config = AiPrefabStore.get().ensureConfig();
        config.prefabFile = target.prefabFile();
        AiPrefabStore.get().saveConfig(config);
        AiPrefabStore.get().reload();

        Universe universe = Universe.get();
        Path worldPath = universe.getPath().resolve("worlds").resolve(targetWorldName);
        Path prefabPath = universe.getPath().resolve(config.prefabFile).normalize();
        if (!Files.exists(prefabPath)) {
            context.sendMessage(Message.raw("Prefab file does not exist: " + prefabPath));
            return;
        }

        World existing = universe.getWorld(targetWorldName);
        if (existing != null) {
            movePlayer(context, playerRef, existing);
            return;
        }

        WorldConfig worldConfig = new WorldConfig();
        worldConfig.setUuid(UUID.randomUUID());
        worldConfig.setDisplayName(targetWorldName);
        worldConfig.setWorldGenProvider(new AiPrefabWorldGenProvider());
        worldConfig.setSavingConfig(true);
        worldConfig.setCanSaveChunks(true);
        worldConfig.setSaveNewChunks(true);
        worldConfig.setBlockTicking(false);
        worldConfig.setTicking(true);

        universe.makeWorld(targetWorldName, worldPath, worldConfig).whenComplete((createdWorld, throwable) -> {
            if (throwable != null) {
                String reason = throwable.getCause() != null ? throwable.getCause().getMessage() : throwable.getMessage();
                playerRef.sendMessage(Message.raw("Failed to create AI prefab world: " + reason));
                return;
            }
            movePlayer(context, playerRef, createdWorld);
        });
    }

    private static void enterExisting(
        @Nonnull CommandContext context,
        @Nonnull PlayerRef playerRef,
        @Nullable String worldName
    ) {
        String targetWorldName = worldName == null || worldName.isBlank() ? DEFAULT_WORLD_NAME : worldName;
        World target = Universe.get().getWorld(targetWorldName);
        if (target == null) {
            context.sendMessage(Message.raw("World is not loaded. Use /aiprefab create " + targetWorldName));
            return;
        }
        movePlayer(context, playerRef, target);
    }

    private static void movePlayer(@Nonnull CommandContext context, @Nonnull PlayerRef playerRef, @Nonnull World targetWorld) {
        Universe universe = Universe.get();
        World currentWorld = resolvePlayerWorld(playerRef, universe);
        AiPrefabConfig config = AiPrefabStore.get().ensureConfig();
        Transform spawn = new Transform(
            config.spawnX + 0.5D,
            config.spawnY,
            config.spawnZ + 0.5D
        );

        playerRef.removeFromStore();
        targetWorld.addPlayer(playerRef, spawn, Boolean.TRUE, Boolean.FALSE).whenComplete((ignored, throwable) -> {
            if (throwable != null) {
                currentWorld.addPlayer(playerRef, null, Boolean.TRUE, Boolean.FALSE);
                String reason = throwable.getCause() != null ? throwable.getCause().getMessage() : throwable.getMessage();
                playerRef.sendMessage(Message.raw("Failed to enter AI prefab world: " + reason));
                return;
            }
            playerRef.sendMessage(Message.raw("Entered AI prefab world " + targetWorld.getName() + "."));
        });
    }

    @Nonnull
    private static World resolvePlayerWorld(@Nonnull PlayerRef playerRef, @Nonnull Universe universe) {
        UUID worldUuid = playerRef.getWorldUuid();
        if (worldUuid != null) {
            World world = universe.getWorld(worldUuid);
            if (world != null) {
                return world;
            }
        }
        World fallback = universe.getDefaultWorld();
        if (fallback == null) {
            throw new IllegalStateException("Default world is unavailable.");
        }
        return fallback;
    }

    @Nullable
    private static String arg(@Nonnull List<String> args, int index) {
        return args.size() > index ? args.get(index) : null;
    }

    @Nonnull
    private static CreateTarget resolveCreateTarget(@Nullable String worldName, @Nullable String prefabFile) {
        if (prefabFile != null && !prefabFile.isBlank()) {
            String normalizedPrefab = normalizePrefabFile(prefabFile);
            String targetWorld = worldName == null || worldName.isBlank() ? worldNameFromPrefab(normalizedPrefab) : worldName;
            return new CreateTarget(targetWorld, normalizedPrefab);
        }

        if (worldName != null && !worldName.isBlank()) {
            String candidatePrefab = normalizePrefabFile(worldName);
            Path candidatePath = Universe.get().getPath().resolve(candidatePrefab).normalize();
            if (Files.exists(candidatePath) || looksLikePrefabName(worldName)) {
                return new CreateTarget(worldNameFromPrefab(candidatePrefab), candidatePrefab);
            }
            return new CreateTarget(worldName, AiPrefabStore.get().ensureConfig().prefabFile);
        }

        return new CreateTarget(DEFAULT_WORLD_NAME, AiPrefabStore.get().ensureConfig().prefabFile);
    }

    @Nonnull
    private static String normalizePrefabFile(@Nonnull String raw) {
        String value = raw.replace('\\', '/');
        if (value.endsWith(".prefab")) {
            value = value + ".json";
        } else if (!value.endsWith(".prefab.json")) {
            value = value + ".prefab.json";
        }
        if (!value.contains("/")) {
            value = "prefabs/" + value;
        }
        return value;
    }

    private static boolean looksLikePrefabName(@Nonnull String value) {
        return value.endsWith(".prefab") || value.endsWith(".prefab.json");
    }

    @Nonnull
    private static String worldNameFromPrefab(@Nonnull String prefabFile) {
        String name = prefabFile.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        if (name.endsWith(".prefab.json")) {
            name = name.substring(0, name.length() - ".prefab.json".length());
        }
        return name.isBlank() ? DEFAULT_WORLD_NAME : name;
    }

    private static int effectiveFlatSurfaceY(@Nonnull AiPrefabConfig config) {
        return config.flatSurfaceY >= 0 ? config.flatSurfaceY : config.spawnY - 1;
    }

    private record CreateTarget(@Nonnull String worldName, @Nonnull String prefabFile) {
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
}
