package com.example.exampleplugin.terrain;

public final class AiTerrainSettings {
    public static final String DEFAULT_PYTHON_ENDPOINT = "http://localhost:8080";
    public static final int REQUEST_TIMEOUT_MILLIS = 1_200_000;
    public static final int DOWNLOAD_TIMEOUT_MILLIS = 30_000;
    public static final int DEFAULT_BASE_Y = 63;
    public static final int DEFAULT_SEA_LEVEL = 63;
    public static final int DEFAULT_SCALE = 1;
    public static final int DEFAULT_ARENA_SIZE = 64;
    public static final int DEFAULT_ARENA_FEATHER = 6;
    public static final int DEFAULT_GRID_SIZE = 3;
    public static final int DEFAULT_OVERLAP = 64;
    public static final float DEFAULT_IMG2IMG_STRENGTH = 0.6f;
    public static final PlacementMode PLACEMENT_MODE = PlacementMode.SOLID;
    public static final int PLACEMENT_TILE_SIZE = 32;
    public static final int MAX_PLACEMENT_HEIGHT = 319;
    public static final int SEED_SALT = 1337;
    public static final int PLACEMENT_PROGRESS_EVERY_TILES = 64;
    public static final int MAX_TREE_SLOPE_DELTA = 3;
    public static final int MAX_DECORATION_SLOPE_DELTA = 4;
    public static final String DEFAULT_GENERATED_WORLD_PREFIX = "aiworld_";
    public static final boolean DEFAULT_TELEPORT_AFTER_COMPLETE = true;
    public static final boolean DEFAULT_DELETE_FAILED_WORLDS = true;

    private AiTerrainSettings() {
    }

    public enum PlacementMode {
        SURFACE_ONLY,
        SOLID
    }
}
