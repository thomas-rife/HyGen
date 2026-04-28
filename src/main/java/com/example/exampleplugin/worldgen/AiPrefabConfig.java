package com.example.exampleplugin.worldgen;

public final class AiPrefabConfig {
    public String prefabFile = "prefabs/my_hytale_map.prefab.json";
    public int originX = 0;
    public int originY = 0;
    public int originZ = 0;
    public int spawnX = 0;
    public int spawnY = 128;
    public int spawnZ = 0;
    public String environment = "Default";
    public boolean flatWorld = true;
    public int flatSurfaceY = -1;
    public String flatBaseBlock = "Rock_Stone";
    public String flatSubSurfaceBlock = "Soil_Dirt";
    public String flatSurfaceBlock = "Soil_Grass";
    public String snowBlockFallback = "Soil_Grass";
}
