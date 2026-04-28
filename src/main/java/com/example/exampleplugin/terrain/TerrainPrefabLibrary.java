package com.example.exampleplugin.terrain;

import com.hypixel.hytale.builtin.buildertools.utils.RecursivePrefabLoader;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.Axis;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.prefab.PrefabStore;
import com.hypixel.hytale.server.core.prefab.selection.standard.BlockSelection;
import com.hypixel.hytale.server.core.universe.world.World;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.logging.Level;
import java.util.stream.Stream;

public final class TerrainPrefabLibrary {
    private static final HytaleLogger LOGGER = HytaleLogger.get("HyGen|AiTerrain");

    private final RecursivePrefabLoader.BlockSelectionLoader loader;
    private final List<Path> prefabRoots;
    private final Map<String, BlockSelection> cache = new HashMap<>();
    private final Set<String> missingLogged = new HashSet<>();

    public TerrainPrefabLibrary() {
        this.loader = new RecursivePrefabLoader.BlockSelectionLoader(
            PrefabStore.get().getAssetPrefabsPath(),
            key -> PrefabStore.get().getAssetPrefab(key)
        );
        this.prefabRoots = loadPrefabRoots();
    }

    @Nullable
    public BlockSelection load(@Nonnull String path, long seed, int rotationSteps) {
        String normalized = normalize(path);
        BlockSelection cachedSelection = this.cache.computeIfAbsent(normalized, key -> loadResolvedPrefab(key, seed));
        if (cachedSelection == null) {
            return null;
        }
        BlockSelection selection = cachedSelection.cloneSelection();
        int steps = Math.floorMod(rotationSteps, 4);
        if (steps != 0) {
            selection = selection.rotate(Axis.Y, steps * 90);
        }
        return selection;
    }

    public void place(@Nonnull World world, @Nonnull BlockSelection selection, int worldX, int worldY, int worldZ) {
        selection.place(null, world, new Vector3i(worldX, worldY, worldZ), null);
    }

    @Nullable
    private BlockSelection loadResolvedPrefab(@Nonnull String key, long seed) {
        List<String> candidates = buildCandidates(key);
        RuntimeException lastFailure = null;
        for (String candidate : candidates) {
            try {
                return this.loader.load(candidate, new Random(seed ^ candidate.hashCode()));
            } catch (RuntimeException e) {
                lastFailure = e;
            }
        }

        String scanned = scanForNestedPrefabKey(key);
        if (scanned != null) {
            try {
                return this.loader.load(scanned, new Random(seed ^ scanned.hashCode()));
            } catch (RuntimeException e) {
                lastFailure = e;
            }
        }

        if (this.missingLogged.add(key)) {
            if (lastFailure != null) {
                LOGGER.at(Level.WARNING).log("Skipping missing prefab path '%s': %s", key, lastFailure.getMessage());
            } else {
                LOGGER.at(Level.WARNING).log("Skipping missing prefab path '%s'", key);
            }
        }
        return null;
    }

    @Nonnull
    private static List<String> buildCandidates(@Nonnull String key) {
        List<String> candidates = new ArrayList<>();
        String slashKey = key.replace('\\', '/');
        String trimmedSlash = slashKey.endsWith("/") ? slashKey.substring(0, slashKey.length() - 1) : slashKey;
        String exact = toDotKey(trimmedSlash);
        if (!exact.isBlank()) {
            candidates.add(exact);
            if (!exact.endsWith(".prefab.json")) {
                candidates.add(exact + ".*");
            }
        }
        if (slashKey.endsWith("/")) {
            String wildcard = toDotKey(trimmedSlash) + ".*";
            if (!wildcard.equals(".*")) {
                candidates.add(wildcard);
            }
        }
        return dedupe(candidates);
    }

    @Nullable
    private String scanForNestedPrefabKey(@Nonnull String key) {
        String normalized = normalize(key);
        String suffix = normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
        if (suffix.isBlank()) {
            return null;
        }
        String suffixWithSlash = "/" + suffix.toLowerCase(Locale.ROOT) + "/";
        List<Path> matches = new ArrayList<>();
        for (Path root : this.prefabRoots) {
            Path start = root.resolve(suffix);
            if (Files.isDirectory(start)) {
                collectPrefabFiles(start, matches);
                continue;
            }
            try (Stream<Path> stream = Files.walk(root)) {
                stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".prefab.json"))
                    .filter(path -> path.toString().replace('\\', '/').toLowerCase(Locale.ROOT).contains(suffixWithSlash))
                    .forEach(matches::add);
            } catch (IOException ignored) {
            }
        }
        if (matches.isEmpty()) {
            return null;
        }
        matches.sort(Comparator.comparing(path -> path.toString().length()));
        return relativeKey(matches.getFirst());
    }

    private void collectPrefabFiles(@Nonnull Path directory, @Nonnull List<Path> matches) {
        try (Stream<Path> stream = Files.walk(directory)) {
            stream
                .filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".prefab.json"))
                .forEach(matches::add);
        } catch (IOException ignored) {
        }
    }

    @Nonnull
    private String relativeKey(@Nonnull Path prefabPath) {
        for (Path root : this.prefabRoots) {
            if (prefabPath.startsWith(root)) {
                String relative = root.relativize(prefabPath).toString().replace('\\', '/');
                if (relative.endsWith(".prefab.json")) {
                    relative = relative.substring(0, relative.length() - ".prefab.json".length());
                }
                return toDotKey(relative);
            }
        }
        String relative = prefabPath.getFileName().toString();
        if (relative.endsWith(".prefab.json")) {
            relative = relative.substring(0, relative.length() - ".prefab.json".length());
        }
        return toDotKey(relative);
    }

    @Nonnull
    private List<Path> loadPrefabRoots() {
        List<Path> roots = new ArrayList<>();
        roots.add(PrefabStore.get().getAssetPrefabsPath());
        for (PrefabStore.AssetPackPrefabPath packPath : PrefabStore.get().getAllAssetPrefabPaths()) {
            roots.add(packPath.prefabsPath());
        }
        roots.add(PrefabStore.get().getServerPrefabsPath());
        List<Path> deduped = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Path root : roots) {
            String normalized = root.toAbsolutePath().normalize().toString();
            if (seen.add(normalized) && Files.exists(root)) {
                deduped.add(root);
            }
        }
        return deduped;
    }

    @Nonnull
    private static List<String> dedupe(@Nonnull List<String> values) {
        List<String> output = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank() && seen.add(value)) {
                output.add(value);
            }
        }
        return output;
    }

    @Nonnull
    private static String toDotKey(@Nonnull String path) {
        return path.replace('\\', '/').replace('/', '.');
    }

    @Nonnull
    private static String normalize(@Nonnull String path) {
        return path.trim().replace('\\', '/');
    }
}
