package com.example.exampleplugin.levels.model;

import java.util.ArrayList;
import java.util.List;

public class LevelDefinition {
    public String levelId;
    public String levelName;
    public String mapWorldName;
    public String mapDisplayName;
    public Boolean overviewCamera;
    public Integer orderIndex;
    public Boolean bossBattle;
    public Boolean isEndlessAi = false;
    public Integer recommendedPower;
    public Integer maxPartySize;
    public TransformData playerSpawn;
    public TransformData fallbackReturnSpawn;
    public List<Vector3Data> enemySpawnLocations = new ArrayList<>();
    public List<WaveDefinition> waves = new ArrayList<>();

    public LevelDefinition() {
    }

    public int waveCount() {
        return this.waves == null ? 0 : this.waves.size();
    }
}
