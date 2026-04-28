package com.example.exampleplugin.levels;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.hypixel.hytale.server.core.universe.Universe;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class LevelProgressStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "battleheart-progress.json";
    private static final LevelProgressStore INSTANCE = new LevelProgressStore();

    private final Map<UUID, Set<String>> completedLevels = new HashMap<>();

    private LevelProgressStore() {
        load();
    }

    @Nonnull
    public static LevelProgressStore get() {
        return INSTANCE;
    }

    public synchronized void completeLevel(@Nonnull UUID playerUuid, @Nonnull String levelId) {
        this.completedLevels.computeIfAbsent(playerUuid, k -> new HashSet<>()).add(levelId.toLowerCase());
        save();
    }

    public synchronized boolean isLevelCompleted(@Nonnull UUID playerUuid, @Nonnull String levelId) {
        Set<String> completed = this.completedLevels.get(playerUuid);
        return completed != null && completed.contains(levelId.toLowerCase());
    }

    private synchronized void load() {
        Path path = getFilePath();
        if (!Files.exists(path)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(path)) {
            Map<UUID, Set<String>> loaded = GSON.fromJson(reader, new TypeToken<Map<UUID, Set<String>>>(){}.getType());
            if (loaded != null) {
                this.completedLevels.clear();
                this.completedLevels.putAll(loaded);
            }
        } catch (IOException e) {
            System.err.println("[Battleheart] Failed to load progress: " + e.getMessage());
        }
    }

    private synchronized void save() {
        Path path = getFilePath();
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (Writer writer = Files.newBufferedWriter(path)) {
                GSON.toJson(this.completedLevels, writer);
            }
        } catch (IOException e) {
            System.err.println("[Battleheart] Failed to save progress: " + e.getMessage());
        }
    }

    @Nonnull
    private Path getFilePath() {
        return Universe.get().getPath().resolve(FILE_NAME);
    }
}
