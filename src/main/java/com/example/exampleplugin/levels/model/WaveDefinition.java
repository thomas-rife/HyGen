package com.example.exampleplugin.levels.model;

import java.util.ArrayList;
import java.util.List;

public class WaveDefinition {
    public Integer waveNumber;
    public Boolean bossWave;
    public Integer startDelayMs;
    public Integer interWaveDelayMs;
    public List<EnemySpawnDefinition> enemies = new ArrayList<>();

    public WaveDefinition() {
    }
}
