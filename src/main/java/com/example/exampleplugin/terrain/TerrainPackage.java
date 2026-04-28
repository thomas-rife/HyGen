package com.example.exampleplugin.terrain;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class TerrainPackage {
    private final Metadata metadata;
    private final short[] heightmap;
    private final byte[] materialmap;
    private final byte[] watermap;
    private final int[] waterHeight;
    private final List<DecorationIntent> decorations;

    public TerrainPackage(
        @Nonnull Metadata metadata,
        @Nonnull short[] heightmap,
        @Nonnull byte[] materialmap,
        @Nonnull byte[] watermap,
        @Nullable int[] waterHeight,
        @Nullable List<DecorationIntent> decorations
    ) {
        int expected = metadata.width * metadata.depth;
        if (heightmap.length != expected) {
            throw new IllegalArgumentException("Heightmap size mismatch. Expected " + expected + " values, got " + heightmap.length);
        }
        if (materialmap.length != expected) {
            throw new IllegalArgumentException("Material map size mismatch. Expected " + expected + " values, got " + materialmap.length);
        }
        if (watermap.length != expected) {
            throw new IllegalArgumentException("Water map size mismatch. Expected " + expected + " values, got " + watermap.length);
        }
        if (waterHeight != null && waterHeight.length != expected) {
            throw new IllegalArgumentException("Water height map size mismatch. Expected " + expected + " values, got " + waterHeight.length);
        }

        this.metadata = metadata;
        this.heightmap = heightmap;
        this.materialmap = materialmap;
        this.watermap = watermap;
        this.waterHeight = waterHeight == null ? null : waterHeight.clone();
        this.decorations = decorations == null ? List.of() : List.copyOf(decorations);
    }

    @Nonnull
    public Metadata metadata() {
        return metadata;
    }

    public int width() {
        return metadata.width;
    }

    public int depth() {
        return metadata.depth;
    }

    public int heightAt(int x, int z) {
        return Short.toUnsignedInt(heightmap[index(x, z)]);
    }

    public int minHeight() {
        int min = Integer.MAX_VALUE;
        for (short value : this.heightmap) {
            min = Math.min(min, Short.toUnsignedInt(value));
        }
        return min == Integer.MAX_VALUE ? 0 : min;
    }

    public int maxHeight() {
        int max = Integer.MIN_VALUE;
        for (short value : this.heightmap) {
            max = Math.max(max, Short.toUnsignedInt(value));
        }
        return max == Integer.MIN_VALUE ? 0 : max;
    }

    public int heightPercentile(double percentile) {
        if (this.heightmap.length == 0) {
            return 0;
        }
        double clamped = Math.max(0.0D, Math.min(1.0D, percentile));
        int[] sorted = new int[this.heightmap.length];
        for (int i = 0; i < this.heightmap.length; i++) {
            sorted[i] = Short.toUnsignedInt(this.heightmap[i]);
        }
        Arrays.sort(sorted);
        int index = (int) Math.round(clamped * (sorted.length - 1));
        index = Math.max(0, Math.min(sorted.length - 1, index));
        return sorted[index];
    }

    public int materialIdAt(int x, int z) {
        return Byte.toUnsignedInt(materialmap[index(x, z)]);
    }

    public boolean isWaterAt(int x, int z) {
        return Byte.toUnsignedInt(watermap[index(x, z)]) == 1;
    }

    public boolean hasWaterHeightmap() {
        return waterHeight != null;
    }

    public int waterHeightAt(int x, int z) {
        int index = index(x, z);
        if (waterHeight == null) {
            return metadata.seaLevel;
        }
        int value = waterHeight[index];
        return value > 0 ? value : metadata.seaLevel;
    }

    @Nonnull
    public String materialNameAt(int x, int z) {
        return metadata.resolveMaterialName(materialIdAt(x, z));
    }

    @Nonnull
    public List<DecorationIntent> decorations() {
        return decorations;
    }

    private int index(int x, int z) {
        if (x < 0 || x >= metadata.width || z < 0 || z >= metadata.depth) {
            throw new IndexOutOfBoundsException("Terrain position out of bounds: (" + x + ", " + z + ")");
        }
        return z * metadata.width + x;
    }

    public static final class Metadata {
        public int version = 1;
        public int width;
        public int depth;
        public int originX;
        public int originZ;
        public int baseY = AiTerrainSettings.DEFAULT_BASE_Y;
        public int seaLevel = AiTerrainSettings.DEFAULT_SEA_LEVEL;
        public int scale = AiTerrainSettings.DEFAULT_SCALE;
        public int arenaSize = 0;
        public int arenaFeather = 0;
        public String prompt = "";
        public Map<String, MaterialRule> blockMappings = Collections.emptyMap();
        public Map<String, String> materialNames = Collections.emptyMap();
        public Map<String, Object> materialIds = Collections.emptyMap();

        public void validate() {
            if (width <= 0 || depth <= 0) {
                throw new IllegalArgumentException("Terrain package width/depth must be > 0");
            }
            if (scale <= 0) {
                throw new IllegalArgumentException("Terrain package scale must be > 0");
            }
            if (arenaSize < 0 || arenaFeather < 0) {
                throw new IllegalArgumentException("Terrain package arena settings must be >= 0");
            }
            if (prompt == null) {
                prompt = "";
            }
            if (blockMappings == null) {
                blockMappings = Collections.emptyMap();
            }
            if (materialNames == null) {
                materialNames = Collections.emptyMap();
            }
            if (materialIds == null) {
                materialIds = Collections.emptyMap();
            }
        }

        @Nonnull
        public String resolveMaterialName(int materialId) {
            String direct = materialNames.get(Integer.toString(materialId));
            if (direct != null && !direct.isBlank()) {
                return normalizeMaterialName(direct);
            }
            for (Map.Entry<String, Object> entry : materialIds.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();
                if (key == null || value == null) {
                    continue;
                }

                Integer keyAsInt = tryParseInt(key);
                if (keyAsInt != null && keyAsInt == materialId) {
                    String name = Objects.toString(value, "").trim();
                    if (!name.isBlank()) {
                        return normalizeMaterialName(name);
                    }
                }

                Integer valueAsInt = tryParseInt(value);
                if (valueAsInt != null && valueAsInt == materialId) {
                    return normalizeMaterialName(key);
                }
            }
            return switch (materialId) {
                case 0 -> "grass";
                case 1 -> "dirt";
                case 2 -> "stone";
                case 3 -> "sand";
                case 4 -> "snow";
                case 5 -> "mud";
                case 6 -> "gravel";
                case 7 -> "water_floor";
                default -> "grass";
            };
        }

        @Nonnull
        private static String normalizeMaterialName(@Nonnull String value) {
            return value.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        }

        @Nullable
        private static Integer tryParseInt(@Nonnull Object value) {
            if (value instanceof Number number) {
                return number.intValue();
            }
            if (value instanceof String string) {
                try {
                    return Integer.parseInt(string.trim());
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
            return null;
        }
    }

    public static final class MaterialRule {
        public String surfaceBlock = "Soil_Grass";
        public String subsurfaceBlock = "Soil_Dirt";
        public String foundationBlock = "Rock_Stone";
        public String fluidBlock = "Fluid_Water";
        public int topLayers = 4;
    }

    public static final class DecorationIntent {
        public String type;
        public int x;
        public int z;
        public int y;
        public int yOffset;
        public float scale = 1.0f;
        public Map<String, Object> data = Collections.emptyMap();
    }
}
