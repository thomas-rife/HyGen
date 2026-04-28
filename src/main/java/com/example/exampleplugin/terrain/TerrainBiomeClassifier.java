package com.example.exampleplugin.terrain;

import javax.annotation.Nonnull;
import java.util.Locale;

public final class TerrainBiomeClassifier {
    private TerrainBiomeClassifier() {
    }

    @Nonnull
    public static TerrainBiomeProfile classify(@Nonnull TerrainPackage.Metadata metadata) {
        String prompt = metadata.prompt == null ? "" : metadata.prompt.toLowerCase(Locale.ROOT);
        TerrainBiomeProfile.PrimaryBiome primary;
        if (containsAny(prompt, "volcano", "volcanic", "lava", "basalt", "ash", "obsidian", "wasteland", "wastes")) {
            primary = TerrainBiomeProfile.PrimaryBiome.VOLCANIC;
        } else if (containsAny(prompt, "canyon", "grand canyon", "mesa", "badlands", "utah", "red rock", "sandstone")) {
            primary = TerrainBiomeProfile.PrimaryBiome.CANYON_UTAH;
        } else if (containsAny(prompt, "desert", "dune", "dunes", "arid", "sahara", "sand")) {
            primary = TerrainBiomeProfile.PrimaryBiome.DESERT;
        } else if (containsAny(prompt, "snow", "snowy", "alpine", "frozen", "glacier", "tundra", "ice", "mountain lake")) {
            primary = TerrainBiomeProfile.PrimaryBiome.SNOWY_ALPINE;
        } else if (containsAny(prompt, "swamp", "marsh", "bog", "wetland", "mire")) {
            primary = TerrainBiomeProfile.PrimaryBiome.SWAMP;
        } else if (containsAny(prompt, "tropical", "jungle island", "palm island")) {
            primary = TerrainBiomeProfile.PrimaryBiome.TROPICAL_ISLAND;
        } else if (containsAny(prompt, "beach", "coast", "coastline", "shore", "shoreline", "island", "ocean", "sea")) {
            primary = TerrainBiomeProfile.PrimaryBiome.BEACH_COAST;
        } else if (containsAny(prompt, "forest", "woods", "woodland", "jungle", "taiga")) {
            primary = TerrainBiomeProfile.PrimaryBiome.FOREST;
        } else if (containsAny(prompt, "plains", "plain", "meadow", "grassland", "prairie", "steppe", "savanna")) {
            primary = TerrainBiomeProfile.PrimaryBiome.PLAINS_MEADOW;
        } else if (containsAny(prompt, "mountain", "mountains", "cliff", "cliffs", "rocky", "peak")) {
            primary = TerrainBiomeProfile.PrimaryBiome.ROCKY_MOUNTAIN;
        } else {
            primary = TerrainBiomeProfile.PrimaryBiome.DEFAULT;
        }

        return new TerrainBiomeProfile(
            primary,
            containsAny(prompt, "river", "stream", "creek"),
            containsAny(prompt, "lake", "lagoon", "pond"),
            containsAny(prompt, "island"),
            containsAny(prompt, "snow", "snowy", "alpine", "frozen", "glacier", "tundra", "ice"),
            containsAny(prompt, "forest", "woods", "woodland", "jungle", "taiga"),
            containsAny(prompt, "beach", "coast", "coastline", "shore", "shoreline"),
            containsAny(prompt, "canyon", "mesa", "badlands", "utah", "sandstone"),
            containsAny(prompt, "volcano", "volcanic", "lava", "basalt", "ash", "obsidian"),
            containsAny(prompt, "cave", "mushroom", "fantasy"),
            containsAny(prompt, "ancient", "fossil", "dolmen", "crystal")
        );
    }

    private static boolean containsAny(@Nonnull String text, @Nonnull String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    public record TerrainBiomeProfile(
        @Nonnull PrimaryBiome primary,
        boolean hasRiver,
        boolean hasLake,
        boolean hasIsland,
        boolean hasSnow,
        boolean hasForest,
        boolean hasBeach,
        boolean hasCanyon,
        boolean hasVolcanic,
        boolean hasCaveFantasy,
        boolean hasAncientRare
    ) {
        public enum PrimaryBiome {
            VOLCANIC,
            CANYON_UTAH,
            DESERT,
            SNOWY_ALPINE,
            SWAMP,
            BEACH_COAST,
            TROPICAL_ISLAND,
            FOREST,
            PLAINS_MEADOW,
            ROCKY_MOUNTAIN,
            DEFAULT
        }
    }
}
