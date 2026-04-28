package com.example.exampleplugin.terrain;

import javax.annotation.Nonnull;
import java.util.Locale;

public enum AiTerrainPlacementMode {
    FULL_COLUMN,
    SURFACE_SHELL;

    @Nonnull
    public static AiTerrainPlacementMode parse(String value) {
        if (value == null || value.isBlank()) {
            return SURFACE_SHELL;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        for (AiTerrainPlacementMode mode : values()) {
            if (mode.name().equals(normalized)) {
                return mode;
            }
        }
        return SURFACE_SHELL;
    }
}
