package com.example.exampleplugin.terrain;

public enum GenerationJobState {
    CREATING_WORLD,
    REQUESTING_PYTHON,
    DOWNLOADING_PACKAGE,
    PLACING_TERRAIN,
    PLACING_WATER,
    PLACING_DECORATIONS,
    FINALIZING,
    COMPLETE,
    FAILED
}
