package com.example.exampleplugin.terrain;

public final class AiTerrainConfig {
    public String pythonEndpoint = AiTerrainSettings.DEFAULT_PYTHON_ENDPOINT;
    public int requestTimeoutMillis = AiTerrainSettings.REQUEST_TIMEOUT_MILLIS;
    public int downloadTimeoutMillis = AiTerrainSettings.DOWNLOAD_TIMEOUT_MILLIS;
    public String generatedWorldPrefix = AiTerrainSettings.DEFAULT_GENERATED_WORLD_PREFIX;
    public int tileBatchSize = AiTerrainSettings.PLACEMENT_TILE_SIZE;
    public int gridSize = AiTerrainSettings.DEFAULT_GRID_SIZE;
    public int overlap = AiTerrainSettings.DEFAULT_OVERLAP;
    public float img2imgStrength = AiTerrainSettings.DEFAULT_IMG2IMG_STRENGTH;
    public String placementMode = AiTerrainPlacementMode.SURFACE_SHELL.name();
    public int surfaceShellDepth = 8;
    public boolean teleportAfterComplete = AiTerrainSettings.DEFAULT_TELEPORT_AFTER_COMPLETE;
    public boolean deleteFailedWorlds = AiTerrainSettings.DEFAULT_DELETE_FAILED_WORLDS;
}
