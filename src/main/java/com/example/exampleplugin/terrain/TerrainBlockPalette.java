package com.example.exampleplugin.terrain;

import com.example.exampleplugin.terrain.TerrainBiomeClassifier.TerrainBiomeProfile;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;

import javax.annotation.Nonnull;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;

public final class TerrainBlockPalette {
    private static final HytaleLogger LOGGER = HytaleLogger.get("HyGen|AiTerrain");

    private final TerrainPackage terrainPackage;
    private final TerrainPackage.Metadata metadata;
    private final TerrainBiomeProfile biomeProfile;
    private final Map<String, TerrainPackage.MaterialRule> overrides;
    private final int variationSeed;
    private final int snowStartY;
    private final int snowFullY;

    public TerrainBlockPalette(@Nonnull TerrainPackage terrainPackage) {
        this.terrainPackage = terrainPackage;
        this.metadata = terrainPackage.metadata();
        this.biomeProfile = TerrainBiomeClassifier.classify(this.metadata);
        this.overrides = this.metadata.blockMappings;
        this.variationSeed = (this.metadata.prompt == null ? 0 : this.metadata.prompt.hashCode()) ^ AiTerrainSettings.SEED_SALT;
        int percentileStart = terrainPackage.heightPercentile(0.60D);
        int percentileFull = terrainPackage.heightPercentile(0.82D);
        int fallbackStart = this.metadata.seaLevel + 35;
        int fallbackFull = this.metadata.seaLevel + 85;
        if (terrainPackage.maxHeight() - terrainPackage.minHeight() < 32 || percentileFull <= percentileStart) {
            this.snowStartY = fallbackStart;
            this.snowFullY = Math.max(fallbackFull, this.snowStartY + 20);
        } else {
            this.snowStartY = percentileStart;
            this.snowFullY = Math.max(percentileFull, this.snowStartY + 12);
        }
        LOGGER.at(Level.INFO).log("Terrain palette selected: %s", this.biomeProfile.primary().name().toLowerCase(Locale.ROOT));
        logSummary();
    }

    @Nonnull
    public TerrainBiomeProfile biomeProfile() {
        return this.biomeProfile;
    }

    public int snowStartY() {
        return this.snowStartY;
    }

    public int snowFullY() {
        return this.snowFullY;
    }

    @Nonnull
    public TerrainMaterialSelection resolve(int materialId, @Nonnull String materialName, boolean water, int worldX, int worldZ, int surfaceY, int slope) {
        String normalized = normalize(materialName);
        TerrainPackage.MaterialRule override = this.overrides.get(normalized);
        if (override == null) {
            override = this.overrides.get(Integer.toString(materialId));
        }
        if (override != null) {
            return fromRule(override);
        }

        int hash = hash(worldX, worldZ);
        TerrainMaterialSelection base = resolveBase(normalized, water, hash);
        if (!shouldBlendSnow(normalized)) {
            return base;
        }
        return applySnowBlend(base, normalized, surfaceY, slope, worldX, worldZ, hash);
    }

    @Nonnull
    public TerrainMaterialSelection coverSurfaceForSpawn(int worldX, int worldZ) {
        return resolve(0, "grass", false, worldX, worldZ, this.metadata.baseY, 0);
    }

    @Nonnull
    private TerrainMaterialSelection resolveBase(@Nonnull String material, boolean water, int hash) {
        return switch (this.biomeProfile.primary()) {
            case VOLCANIC -> volcanic(material, water, hash);
            case CANYON_UTAH -> canyon(material, water, hash);
            case DESERT -> desert(material, water, hash);
            case SNOWY_ALPINE -> snowy(material, water, hash);
            case SWAMP -> swamp(material, water, hash);
            case BEACH_COAST -> beach(material, water, hash);
            case TROPICAL_ISLAND -> tropical(material, water, hash);
            case FOREST -> forest(material, water, hash);
            case PLAINS_MEADOW -> plains(material, water, hash);
            case ROCKY_MOUNTAIN -> rockyMountain(material, water, hash);
            case DEFAULT -> defaultPalette(material, water, hash);
        };
    }

    private boolean shouldBlendSnow(@Nonnull String material) {
        return this.biomeProfile.primary() == TerrainBiomeProfile.PrimaryBiome.SNOWY_ALPINE
            || this.biomeProfile.hasSnow()
            || "snow".equals(material);
    }

    @Nonnull
    private TerrainMaterialSelection applySnowBlend(
        @Nonnull TerrainMaterialSelection base,
        @Nonnull String material,
        int surfaceY,
        int slope,
        int worldX,
        int worldZ,
        int hash
    ) {
        double heightFactor = smoothstep(this.snowStartY, this.snowFullY, surfaceY);
        double slopePenalty = smoothstep(3.5D, 12.0D, slope) * 0.45D;
        double noise = normalizedNoise(worldX, worldZ) * 0.25D;
        double materialBonus = "snow".equals(material) ? 0.35D : 0.0D;
        double promptBonus = this.biomeProfile.hasSnow() ? 0.1D : 0.0D;
        double snowScore = heightFactor + materialBonus + promptBonus + noise - slopePenalty;
        if (surfaceY <= this.metadata.seaLevel + 8 && !this.biomeProfile.hasSnow() && !"snow".equals(material)) {
            snowScore -= 0.35D;
        }

        boolean exposedSlope = slope >= 6;
        if (snowScore >= 0.75D && !exposedSlope) {
            return select("Soil_Snow", "Soil_Dirt_Cold", coldFoundation(hash), "Fluid_Water");
        }
        if (snowScore >= 0.45D) {
            if (exposedSlope) {
                return select(coldRock(hash), "Soil_Dirt_Cold", coldFoundation(hash), "Fluid_Water");
            }
            return switch (material) {
                case "rock", "stone" -> select(variant(hash, coldRock(hash), "Soil_Snow", "Rock_Stone"), "Rock_Slate", coldFoundation(hash), "Fluid_Water");
                case "snow" -> select(variant(hash, "Soil_Snow", "Soil_Grass_Cold", "Soil_Dirt_Cold"), "Soil_Dirt_Cold", coldFoundation(hash), "Fluid_Water");
                default -> select(variant(hash, "Soil_Snow", "Soil_Grass_Cold", "Soil_Dirt_Cold"), "Soil_Dirt_Cold", coldFoundation(hash), "Fluid_Water");
            };
        }
        if (exposedSlope && ("snow".equals(material) || "rock".equals(material) || "stone".equals(material))) {
            return select(coldRock(hash), "Rock_Slate", coldFoundation(hash), "Fluid_Water");
        }
        return base;
    }

    @Nonnull
    private TerrainMaterialSelection plains(@Nonnull String material, boolean water, int hash) {
        return switch (material) {
            case "grass" -> select(variant(hash, "Soil_Grass_Sunny", "Soil_Grass_Deep", "Soil_Grass_Sunny"), "Soil_Dirt", "Rock_Stone", "Fluid_Water");
            case "dirt" -> select("Soil_Dirt", "Soil_Dirt", "Rock_Stone", "Fluid_Water");
            case "stone", "rock" -> select(variant(hash, "Rock_Stone", "Rock_Stone", "Rock_Stone"), "Rock_Stone", "Rock_Stone", "Fluid_Water");
            case "sand" -> select("Soil_Sand", "Soil_Sand", "Rock_Stone", "Fluid_Water");
            case "snow" -> select("Soil_Snow", "Soil_Dirt", "Rock_Stone", "Fluid_Water");
            case "mud" -> select("Soil_Mud_Dry", "Soil_Dirt", "Rock_Stone", "Fluid_Water");
            case "gravel" -> select("Soil_Gravel", "Soil_Gravel", "Rock_Stone", "Fluid_Water");
            case "water_floor" -> select(variant(hash, "Soil_Gravel", "Soil_Gravel", "Soil_Pebbles"), "Soil_Gravel", "Rock_Stone", "Fluid_Water");
            default -> water ? select("Soil_Gravel", "Soil_Dirt", "Rock_Stone", "Fluid_Water") : select("Soil_Grass_Sunny", "Soil_Dirt", "Rock_Stone", "Fluid_Water");
        };
    }

    @Nonnull
    private TerrainMaterialSelection forest(@Nonnull String material, boolean water, int hash) {
        return switch (material) {
            case "grass" -> select("Soil_Grass_Deep", "Soil_Dirt", "Rock_Stone", "Fluid_Water");
            case "dirt" -> select("Soil_Dirt", "Soil_Dirt", "Rock_Stone", "Fluid_Water");
            case "stone", "rock" -> select(variant(hash, "Rock_Stone_Mossy", "Rock_Stone", "Rock_Stone"), "Rock_Stone", "Rock_Stone", "Fluid_Water");
            case "sand" -> select("Soil_Sand", "Soil_Sand", "Rock_Stone", "Fluid_Water");
            case "snow" -> select("Soil_Snow", "Soil_Dirt", "Rock_Stone", "Fluid_Water");
            case "mud" -> select("Soil_Mud_Dry", "Soil_Dirt", "Rock_Stone", "Fluid_Water");
            case "gravel" -> select(variant(hash, "Soil_Gravel_Mossy", "Soil_Gravel", "Soil_Gravel"), "Soil_Gravel", "Rock_Stone", "Fluid_Water");
            case "water_floor" -> select(variant(hash, "Soil_Mud_Dry", "Soil_Gravel_Mossy", "Soil_Gravel"), "Soil_Mud_Dry", "Rock_Stone", "Fluid_Water");
            default -> water ? select("Soil_Mud_Dry", "Soil_Mud_Dry", "Rock_Stone", "Fluid_Water") : select("Soil_Grass_Deep", "Soil_Dirt", "Rock_Stone", "Fluid_Water");
        };
    }

    @Nonnull
    private TerrainMaterialSelection swamp(@Nonnull String material, boolean water, int hash) {
        return switch (material) {
            case "grass" -> select(variant(hash, "Soil_Grass_Deep", "Soil_Grass_Cold", "Soil_Grass_Deep"), "Soil_Mud_Dry", "Rock_Stone", "Fluid_Water");
            case "dirt", "mud", "sand" -> select("Soil_Mud_Dry", "Soil_Mud_Dry", "Rock_Stone", "Fluid_Water");
            case "stone", "rock" -> select("Rock_Stone", "Rock_Stone", "Rock_Stone", "Fluid_Water");
            case "gravel" -> select("Soil_Gravel_Mossy", "Soil_Mud_Dry", "Rock_Stone", "Fluid_Water");
            case "water_floor" -> select("Soil_Mud_Dry", "Soil_Mud_Dry", "Rock_Stone", "Fluid_Water");
            default -> water ? select("Soil_Mud_Dry", "Soil_Mud_Dry", "Rock_Stone", "Fluid_Water") : select("Soil_Grass_Deep", "Soil_Mud_Dry", "Rock_Stone", "Fluid_Water");
        };
    }

    @Nonnull
    private TerrainMaterialSelection beach(@Nonnull String material, boolean water, int hash) {
        return switch (material) {
            case "grass" -> select("Soil_Grass_Sunny", "Soil_Sand", "Rock_Stone", "Fluid_Water");
            case "dirt", "mud" -> select("Soil_Sand", "Soil_Sand", "Rock_Sandstone_White", "Fluid_Water");
            case "sand" -> select("Soil_Sand_White", "Soil_Sand", "Rock_Sandstone_White", "Fluid_Water");
            case "stone", "rock" -> select(variant(hash, "Rock_Sandstone_White", "Rock_Stone", "Rock_Sandstone_White"), "Rock_Sandstone_White", "Rock_Stone", "Fluid_Water");
            case "gravel" -> select(variant(hash, "Soil_Gravel_Sand", "Soil_Gravel", "Soil_Gravel_Sand"), "Soil_Sand", "Rock_Sandstone_White", "Fluid_Water");
            case "water_floor" -> select(variant(hash, "Soil_Sand_White", "Soil_Gravel_Sand", "Soil_Sand_White"), "Soil_Sand", "Rock_Sandstone_White", "Fluid_Water");
            default -> water ? select("Soil_Sand_White", "Soil_Sand", "Rock_Sandstone_White", "Fluid_Water") : select("Soil_Grass_Sunny", "Soil_Sand", "Rock_Sandstone_White", "Fluid_Water");
        };
    }

    @Nonnull
    private TerrainMaterialSelection desert(@Nonnull String material, boolean water, int hash) {
        return switch (material) {
            case "grass" -> select(variant(hash, "Soil_Grass_Dry", "Soil_Sand_White", "Soil_Grass_Dry"), "Soil_Dirt_Dry", "Rock_Sandstone", "Fluid_Water");
            case "dirt" -> select("Soil_Dirt_Dry", "Soil_Dirt_Dry", "Rock_Sandstone", "Fluid_Water");
            case "sand" -> select("Soil_Sand_White", "Soil_Sand_White", "Rock_Sandstone", "Fluid_Water");
            case "stone" -> select(variant(hash, "Rock_Sandstone_White", "Rock_Sandstone", "Rock_Sandstone"), "Rock_Sandstone", "Rock_Stone", "Fluid_Water");
            case "rock" -> select(variant(hash, "Rock_Sandstone", "Rock_Sandstone", "Rock_Sandstone_White"), "Rock_Sandstone", "Rock_Stone", "Fluid_Water");
            case "gravel" -> select(variant(hash, "Soil_Gravel_Sand", "Soil_Gravel", "Soil_Gravel"), "Soil_Sand_White", "Rock_Sandstone", "Fluid_Water");
            case "mud" -> select("Soil_Mud_Dry", "Soil_Dirt_Dry", "Rock_Sandstone", "Fluid_Water");
            case "water_floor" -> select("Soil_Sand_White", "Soil_Sand_White", "Rock_Sandstone", "Fluid_Water");
            default -> water ? select("Soil_Sand_White", "Soil_Sand_White", "Rock_Sandstone", "Fluid_Water") : select("Soil_Sand_White", "Soil_Dirt_Dry", "Rock_Sandstone", "Fluid_Water");
        };
    }

    @Nonnull
    private TerrainMaterialSelection canyon(@Nonnull String material, boolean water, int hash) {
        return switch (material) {
            case "grass", "dirt" -> select(variant(hash, "Soil_Dirt_Dry", "Soil_Grass_Dry", "Soil_Dirt_Dry"), "Soil_Dirt_Dry", "Rock_Sandstone_Red", "Fluid_Water");
            case "sand" -> select("Soil_Sand_Red", "Soil_Sand_Red", "Rock_Sandstone_Red", "Fluid_Water");
            case "stone" -> select(variant(hash, "Rock_Sandstone_Red", "Rock_Sandstone_Red", "Rock_Sandstone_Red"), "Rock_Sandstone_Red", "Rock_Stone", "Fluid_Water");
            case "rock" -> select(variant(hash, "Rock_Sandstone_Red", "Rock_Shale", "Rock_Sandstone_Red"), "Rock_Sandstone_Red", "Rock_Stone", "Fluid_Water");
            case "gravel" -> select("Soil_Gravel", "Soil_Gravel", "Rock_Sandstone_Red", "Fluid_Water");
            case "mud" -> select(variant(hash, "Soil_Clay", "Soil_Mud_Dry", "Soil_Mud_Dry"), "Soil_Dirt_Dry", "Rock_Sandstone_Red", "Fluid_Water");
            case "water_floor" -> select(variant(hash, "Soil_Gravel", "Soil_Sand_Red", "Soil_Gravel"), "Soil_Gravel", "Rock_Sandstone_Red", "Fluid_Water");
            default -> water ? select("Soil_Gravel", "Soil_Gravel", "Rock_Sandstone_Red", "Fluid_Water") : select("Soil_Dirt_Dry", "Soil_Dirt_Dry", "Rock_Sandstone_Red", "Fluid_Water");
        };
    }

    @Nonnull
    private TerrainMaterialSelection snowy(@Nonnull String material, boolean water, int hash) {
        return switch (material) {
            case "grass" -> select("Soil_Grass_Cold", "Soil_Dirt_Cold", coldFoundation(hash), "Fluid_Water");
            case "dirt", "mud" -> select("Soil_Dirt_Cold", "Soil_Dirt_Cold", coldFoundation(hash), "Fluid_Water");
            case "snow" -> select("Soil_Snow", "Soil_Dirt_Cold", coldFoundation(hash), "Fluid_Water");
            case "stone" -> select(variant(hash, "Rock_Slate", "Rock_Stone", "Rock_Slate"), "Rock_Slate", coldFoundation(hash), "Fluid_Water");
            case "rock" -> select(coldRock(hash), "Rock_Slate", coldFoundation(hash), "Fluid_Water");
            case "gravel", "sand", "water_floor" -> select("Soil_Gravel", "Soil_Gravel", coldFoundation(hash), "Fluid_Water");
            default -> water ? select("Soil_Gravel", "Soil_Gravel", coldFoundation(hash), "Fluid_Water") : select("Soil_Grass_Cold", "Soil_Dirt_Cold", coldFoundation(hash), "Fluid_Water");
        };
    }

    @Nonnull
    private TerrainMaterialSelection rockyMountain(@Nonnull String material, boolean water, int hash) {
        return switch (material) {
            case "grass" -> select(variant(hash, "Soil_Grass_Cold", "Soil_Grass_Sunny", "Soil_Grass_Cold"), "Soil_Dirt", "Rock_Stone", "Fluid_Water");
            case "dirt", "mud" -> select("Soil_Dirt", "Soil_Dirt", "Rock_Stone", "Fluid_Water");
            case "stone" -> select(variant(hash, "Rock_Stone", "Rock_Slate", "Rock_Stone"), "Rock_Stone", "Rock_Stone", "Fluid_Water");
            case "rock" -> select(coldRock(hash), "Rock_Stone", "Rock_Stone", "Fluid_Water");
            case "gravel", "sand", "water_floor" -> select("Soil_Gravel", "Soil_Gravel", "Rock_Stone", "Fluid_Water");
            case "snow" -> select("Soil_Snow", "Soil_Dirt_Cold", "Rock_Slate", "Fluid_Water");
            default -> water ? select("Soil_Gravel", "Soil_Gravel", "Rock_Stone", "Fluid_Water") : select("Soil_Grass_Cold", "Soil_Dirt", "Rock_Stone", "Fluid_Water");
        };
    }

    @Nonnull
    private TerrainMaterialSelection volcanic(@Nonnull String material, boolean water, int hash) {
        return switch (material) {
            case "grass" -> select(variant(hash, "Soil_Ash", "Soil_Grass_Burnt", "Soil_Ash"), "Soil_Ash", "Rock_Basalt", "Fluid_Water");
            case "dirt" -> select(variant(hash, "Soil_Ash", "Soil_Dirt_Burnt", "Soil_Ash"), "Soil_Ash", "Rock_Basalt", "Fluid_Water");
            case "sand" -> select("Soil_Sand_Ashen", "Soil_Ash", "Rock_Basalt", "Fluid_Water");
            case "stone" -> select(variant(hash, "Rock_Basalt", "Rock_Volcanic", "Rock_Magma_Cooled"), "Rock_Basalt", "Rock_Basalt", "Fluid_Water");
            case "rock" -> select(variant(hash, "Rock_Basalt", "Rock_Volcanic", "Rock_Magma_Cooled"), "Rock_Basalt", "Rock_Basalt", "Fluid_Water");
            case "gravel" -> select("Soil_Gravel", "Soil_Ash", "Rock_Basalt", "Fluid_Water");
            case "mud" -> select(variant(hash, "Soil_Ash", "Soil_Mud_Dry", "Soil_Ash"), "Soil_Ash", "Rock_Basalt", "Fluid_Water");
            case "snow" -> select("Soil_Ash", "Soil_Ash", "Rock_Basalt", "Fluid_Water");
            case "water_floor" -> select(variant(hash, "Rock_Basalt", "Soil_Gravel", "Rock_Basalt"), "Rock_Basalt", "Rock_Basalt", "Fluid_Water");
            default -> water ? select("Rock_Basalt", "Rock_Basalt", "Rock_Basalt", "Fluid_Water") : select("Soil_Ash", "Soil_Ash", "Rock_Basalt", "Fluid_Water");
        };
    }

    @Nonnull
    private TerrainMaterialSelection tropical(@Nonnull String material, boolean water, int hash) {
        return switch (material) {
            case "grass" -> select(variant(hash, "Soil_Grass_Sunny", "Soil_Grass_Deep", "Soil_Grass_Sunny"), "Soil_Dirt", "Rock_Stone", "Fluid_Water");
            case "dirt" -> select("Soil_Dirt", "Soil_Dirt", "Rock_Stone", "Fluid_Water");
            case "sand" -> select("Soil_Sand_White", "Soil_Sand_White", "Rock_Sandstone_White", "Fluid_Water");
            case "stone" -> select(variant(hash, "Rock_Sandstone_White", "Rock_Stone", "Rock_Sandstone_White"), "Rock_Sandstone_White", "Rock_Stone", "Fluid_Water");
            case "rock" -> select("Rock_Stone", "Rock_Stone", "Rock_Stone", "Fluid_Water");
            case "gravel" -> select("Soil_Gravel_Sand", "Soil_Sand_White", "Rock_Stone", "Fluid_Water");
            case "water_floor" -> select(variant(hash, "Soil_Sand_White", "Soil_Pebbles", "Soil_Sand_White"), "Soil_Sand_White", "Rock_Stone", "Fluid_Water");
            default -> water ? select("Soil_Sand_White", "Soil_Sand_White", "Rock_Stone", "Fluid_Water") : select("Soil_Grass_Sunny", "Soil_Dirt", "Rock_Stone", "Fluid_Water");
        };
    }

    @Nonnull
    private TerrainMaterialSelection defaultPalette(@Nonnull String material, boolean water, int hash) {
        return plains(material, water, hash);
    }

    @Nonnull
    private TerrainMaterialSelection fromRule(@Nonnull TerrainPackage.MaterialRule rule) {
        return new TerrainMaterialSelection(
            orDefault(rule.surfaceBlock, "Soil_Grass_Sunny"),
            orDefault(rule.subsurfaceBlock, "Soil_Dirt"),
            orDefault(rule.foundationBlock, "Rock_Stone"),
            orDefault(rule.fluidBlock, "Fluid_Water"),
            rule.topLayers
        );
    }

    @Nonnull
    private TerrainMaterialSelection select(@Nonnull String surface, @Nonnull String subsurface, @Nonnull String foundation, @Nonnull String fluid) {
        return new TerrainMaterialSelection(surface, subsurface, foundation, fluid, 4);
    }

    private int hash(int worldX, int worldZ) {
        int h = this.variationSeed;
        h ^= worldX * 0x1f1f1f1f;
        h ^= worldZ * 0x5f356495;
        h ^= (h >>> 16);
        return h & 0x7fffffff;
    }

    private double normalizedNoise(int worldX, int worldZ) {
        return ((hash(worldX, worldZ) & 1023) / 511.5D) - 1.0D;
    }

    private static double smoothstep(double edge0, double edge1, double value) {
        if (edge1 <= edge0) {
            return value >= edge1 ? 1.0D : 0.0D;
        }
        double t = Math.max(0.0D, Math.min(1.0D, (value - edge0) / (edge1 - edge0)));
        return t * t * (3.0D - (2.0D * t));
    }

    @Nonnull
    private static String coldFoundation(int hash) {
        return variant(hash, "Rock_Slate", "Rock_Quartzite", "Rock_Stone");
    }

    @Nonnull
    private static String coldRock(int hash) {
        return variant(hash, "Rock_Slate", "Rock_Quartzite", "Rock_Stone");
    }

    @Nonnull
    private static String variant(int hash, @Nonnull String main, @Nonnull String secondary, @Nonnull String tertiary) {
        int value = hash % 100;
        if (value >= 95) {
            return tertiary;
        }
        if (value >= 80) {
            return secondary;
        }
        return main;
    }

    @Nonnull
    private static String normalize(@Nonnull String value) {
        return value.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }

    @Nonnull
    private static String orDefault(String value, @Nonnull String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    public static int blockId(@Nonnull String key, @Nonnull String fallback) {
        int direct = BlockType.getAssetMap().getIndex(key);
        if (direct != Integer.MIN_VALUE) {
            return direct;
        }
        int secondary = BlockType.getAssetMap().getIndex(fallback);
        if (secondary != Integer.MIN_VALUE) {
            return secondary;
        }
        return BlockType.getBlockIdOrUnknown(fallback, "Failed to find AI terrain block '%s'", fallback);
    }

    private void logSummary() {
        String label = switch (this.biomeProfile.primary()) {
            case VOLCANIC -> "grass -> Soil_Ash / Soil_Grass_Burnt | stone -> Rock_Basalt / Rock_Volcanic / Rock_Magma_Cooled";
            case CANYON_UTAH -> "sand -> Soil_Sand_Red | rock -> Rock_Sandstone_Red / Rock_Shale";
            case DESERT -> "sand -> Soil_Sand_White | rock -> Rock_Sandstone / Rock_Sandstone_White";
            case SNOWY_ALPINE -> "grass -> Soil_Grass_Cold | snow score -> Soil_Snow / cold grass / slate";
            case SWAMP -> "mud -> Soil_Mud_Dry | grass -> Soil_Grass_Deep / Soil_Grass_Cold";
            case BEACH_COAST -> "sand -> Soil_Sand_White | rock -> Rock_Sandstone_White / Rock_Stone";
            case TROPICAL_ISLAND -> "grass -> Soil_Grass_Sunny / Soil_Grass_Deep | shore -> Soil_Sand_White";
            case FOREST -> "grass -> Soil_Grass_Deep | rock -> Rock_Stone_Mossy / Rock_Stone";
            case PLAINS_MEADOW -> "grass -> Soil_Grass_Sunny / Soil_Grass_Deep | gravel -> Soil_Gravel / Soil_Pebbles";
            case ROCKY_MOUNTAIN -> "rock -> Rock_Slate / Rock_Quartzite / Rock_Stone";
            case DEFAULT -> "default -> plains meadow";
        };
        LOGGER.at(Level.INFO).log("  %s", label);
        if (this.biomeProfile.hasSnow() || this.biomeProfile.primary() == TerrainBiomeProfile.PrimaryBiome.SNOWY_ALPINE) {
            LOGGER.at(Level.INFO).log("Snow thresholds: start=%s full=%s", this.snowStartY, this.snowFullY);
        }
    }
}
