package com.example.exampleplugin.worldgen;

import com.hypixel.hytale.server.core.prefab.selection.standard.BlockSelection;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class AiPrefabVolume {
    private static final int CHUNK_SIZE = 32;

    private final Map<Long, List<PlacedBlock>> blocksByChunk = new HashMap<>();
    private int minX = Integer.MAX_VALUE;
    private int minY = Integer.MAX_VALUE;
    private int minZ = Integer.MAX_VALUE;
    private int maxXExclusive = Integer.MIN_VALUE;
    private int maxYExclusive = Integer.MIN_VALUE;
    private int maxZExclusive = Integer.MIN_VALUE;

    public AiPrefabVolume() {
    }

    public AiPrefabVolume(@Nonnull BlockSelection selection, int originX, int originY, int originZ) {
        int anchorX = selection.getAnchorX();
        int anchorY = selection.getAnchorY();
        int anchorZ = selection.getAnchorZ();

        selection.forEachBlock((x, y, z, block) -> {
            int worldX = originX + x - anchorX;
            int worldY = originY + y - anchorY;
            int worldZ = originZ + z - anchorZ;

            addWorldBlock(worldX, worldY, worldZ, block.blockId(), block.rotation(), block.filler());
        });
    }

    public void addWorldBlock(int worldX, int worldY, int worldZ, int blockId, int rotation, int filler) {
        int chunkX = Math.floorDiv(worldX, CHUNK_SIZE);
        int chunkZ = Math.floorDiv(worldZ, CHUNK_SIZE);
        int localX = Math.floorMod(worldX, CHUNK_SIZE);
        int localZ = Math.floorMod(worldZ, CHUNK_SIZE);

        this.minX = Math.min(this.minX, worldX);
        this.minY = Math.min(this.minY, worldY);
        this.minZ = Math.min(this.minZ, worldZ);
        this.maxXExclusive = Math.max(this.maxXExclusive, worldX + 1);
        this.maxYExclusive = Math.max(this.maxYExclusive, worldY + 1);
        this.maxZExclusive = Math.max(this.maxZExclusive, worldZ + 1);

        PlacedBlock placed = new PlacedBlock(localX, worldY, localZ, blockId, rotation, filler);
        this.blocksByChunk.computeIfAbsent(chunkKey(chunkX, chunkZ), ignored -> new ArrayList<>()).add(placed);
    }

    public boolean intersectsChunk(int chunkX, int chunkZ) {
        return this.blocksByChunk.containsKey(chunkKey(chunkX, chunkZ));
    }

    @Nonnull
    public List<PlacedBlock> blocksForChunk(int chunkX, int chunkZ) {
        List<PlacedBlock> blocks = this.blocksByChunk.get(chunkKey(chunkX, chunkZ));
        return blocks == null ? List.of() : blocks;
    }

    public int minX() {
        return this.minX;
    }

    public int minY() {
        return this.minY;
    }

    public int minZ() {
        return this.minZ;
    }

    public int maxXExclusive() {
        return this.maxXExclusive;
    }

    public int maxYExclusive() {
        return this.maxYExclusive;
    }

    public int maxZExclusive() {
        return this.maxZExclusive;
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xffffffffL);
    }

    public record PlacedBlock(int localX, int y, int localZ, int blockId, int rotation, int filler) {
    }
}
