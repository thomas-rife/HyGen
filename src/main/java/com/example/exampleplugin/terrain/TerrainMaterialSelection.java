package com.example.exampleplugin.terrain;

import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;

import javax.annotation.Nonnull;

public final class TerrainMaterialSelection {
    public final String surfaceName;
    public final String subsurfaceName;
    public final String foundationName;
    public final String fluidName;
    public final int surfaceId;
    public final int subsurfaceId;
    public final int foundationId;
    public final int fluidId;
    public final BlockType surfaceType;
    public final BlockType subsurfaceType;
    public final BlockType foundationType;
    public final BlockType fluidType;
    public final int topLayers;

    public TerrainMaterialSelection(
        @Nonnull String surfaceName,
        @Nonnull String subsurfaceName,
        @Nonnull String foundationName,
        @Nonnull String fluidName,
        int topLayers
    ) {
        this.surfaceName = surfaceName;
        this.subsurfaceName = subsurfaceName;
        this.foundationName = foundationName;
        this.fluidName = fluidName;
        this.surfaceId = TerrainBlockPalette.blockId(surfaceName, "Soil_Grass_Sunny");
        this.subsurfaceId = TerrainBlockPalette.blockId(subsurfaceName, "Soil_Dirt");
        this.foundationId = TerrainBlockPalette.blockId(foundationName, "Rock_Stone");
        this.fluidId = TerrainBlockPalette.blockId(fluidName, "Fluid_Water");
        this.surfaceType = BlockType.getAssetMap().getAsset(this.surfaceId);
        this.subsurfaceType = BlockType.getAssetMap().getAsset(this.subsurfaceId);
        this.foundationType = BlockType.getAssetMap().getAsset(this.foundationId);
        this.fluidType = BlockType.getAssetMap().getAsset(this.fluidId);
        this.topLayers = Math.max(1, topLayers);
    }
}
