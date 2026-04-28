package com.example.exampleplugin.terrain;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.hypixel.hytale.logger.HytaleLogger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.logging.Level;

public final class TerrainPackageLoader {
    private static final Gson GSON = new GsonBuilder().create();
    private static final HytaleLogger LOGGER = HytaleLogger.get("HyGen|AiTerrain");
    private static final Type DECORATION_LIST_TYPE = new TypeToken<List<TerrainPackage.DecorationIntent>>() {}.getType();

    @Nonnull
    public TerrainPackage load(@Nonnull TerrainPackageClient client, @Nonnull TerrainPackageDescriptor descriptor) throws IOException, InterruptedException {
        TerrainPackage.Metadata metadata = GSON.fromJson(client.fetchUtf8(descriptor.metadataUri()), TerrainPackage.Metadata.class);
        if (metadata == null) {
            throw new IOException("Terrain metadata was empty: " + descriptor.metadataUri());
        }
        metadata.validate();

        short[] heightmap = decodeHeightmap(client.fetchBytes(descriptor.heightmapUri()), metadata.width, metadata.depth);
        byte[] materialmap = decodeByteMap(client.fetchBytes(descriptor.materialmapUri()), metadata.width, metadata.depth, "material map");
        byte[] watermap = decodeByteMap(client.fetchBytes(descriptor.watermapUri()), metadata.width, metadata.depth, "water map");
        int[] waterHeight = loadOptionalWaterHeight(client, descriptor, metadata.width, metadata.depth);

        List<TerrainPackage.DecorationIntent> decorations = Collections.emptyList();
        List<TerrainPackage.DecorationIntent> decoded = decodeDecorations(client.fetchBytes(descriptor.decorationsUri()));
        if (decoded != null) {
            decorations = decoded;
        }
        LOGGER.at(Level.INFO).log(
            "Loaded terrain package: width=%s, depth=%s, total_cells=%s",
            metadata.width,
            metadata.depth,
            metadata.width * metadata.depth
        );
        return new TerrainPackage(metadata, heightmap, materialmap, watermap, waterHeight, decorations);
    }

    @Nonnull
    private static short[] decodeHeightmap(@Nonnull byte[] gzippedBytes, int width, int depth) throws IOException {
        int expectedValues = width * depth;
        byte[] raw = unzip(gzippedBytes);
        if (raw.length != expectedValues * Short.BYTES) {
            throw new IOException("Heightmap byte count mismatch. Expected " + (expectedValues * Short.BYTES) + " bytes, got " + raw.length);
        }

        short[] out = new short[expectedValues];
        ByteBuffer buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < expectedValues; i++) {
            out[i] = buffer.getShort();
        }
        return out;
    }

    @Nonnull
    private static byte[] decodeByteMap(@Nonnull byte[] gzippedBytes, int width, int depth, @Nonnull String label) throws IOException {
        int expectedValues = width * depth;
        byte[] raw = unzip(gzippedBytes);
        if (raw.length != expectedValues) {
            throw new IOException(label + " byte count mismatch. Expected " + expectedValues + " bytes, got " + raw.length);
        }
        return raw;
    }

    @Nonnull
    private static List<TerrainPackage.DecorationIntent> decodeDecorations(@Nonnull byte[] gzippedBytes) throws IOException {
        String json = new String(unzip(gzippedBytes), StandardCharsets.UTF_8);
        JsonElement element = GSON.fromJson(json, JsonElement.class);
        if (element == null || element.isJsonNull()) {
            return Collections.emptyList();
        }
        if (element.isJsonArray()) {
            List<TerrainPackage.DecorationIntent> list = GSON.fromJson(element, DECORATION_LIST_TYPE);
            return list == null ? Collections.emptyList() : list;
        }
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            JsonElement decorations = object.get("decorations");
            if (decorations != null && decorations.isJsonArray()) {
                List<TerrainPackage.DecorationIntent> list = GSON.fromJson(decorations, DECORATION_LIST_TYPE);
                return list == null ? Collections.emptyList() : list;
            }
        }
        return Collections.emptyList();
    }

    @Nullable
    private static int[] loadOptionalWaterHeight(
        @Nonnull TerrainPackageClient client,
        @Nonnull TerrainPackageDescriptor descriptor,
        int width,
        int depth
    ) throws IOException, InterruptedException {
        byte[] gzippedBytes;
        try {
            gzippedBytes = client.fetchBytes(descriptor.waterheightUri());
        } catch (IOException e) {
            String message = e.getMessage();
            if (message != null && message.contains("HTTP 404")) {
                LOGGER.at(Level.INFO).log("waterheight.bin.gz missing; falling back to seaLevel");
                return null;
            }
            throw e;
        }

        int[] waterHeight = decodeUint16LittleEndian(unzip(gzippedBytes), width * depth, "water height map");
        logWaterHeightStats(waterHeight);
        return waterHeight;
    }

    @Nonnull
    private static int[] decodeUint16LittleEndian(@Nonnull byte[] bytes, int expectedValues, @Nonnull String name) throws IOException {
        int expectedBytes = expectedValues * 2;
        if (bytes.length != expectedBytes) {
            throw new IOException(name + " length mismatch: got " + bytes.length + " bytes, expected " + expectedBytes);
        }

        int[] values = new int[expectedValues];
        for (int i = 0; i < expectedValues; i++) {
            int lo = bytes[i * 2] & 0xFF;
            int hi = bytes[i * 2 + 1] & 0xFF;
            values[i] = lo | (hi << 8);
        }
        return values;
    }

    private static void logWaterHeightStats(@Nonnull int[] waterHeight) {
        int nonzero = 0;
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int value : waterHeight) {
            if (value <= 0) {
                continue;
            }
            nonzero++;
            min = Math.min(min, value);
            max = Math.max(max, value);
        }
        if (nonzero == 0) {
            LOGGER.at(Level.INFO).log("Loaded waterheight: nonzero=0");
            return;
        }
        LOGGER.at(Level.INFO).log("Loaded waterheight: nonzero=%s min=%s max=%s", nonzero, min, max);
    }

    @Nonnull
    private static byte[] unzip(@Nonnull byte[] gzippedBytes) throws IOException {
        try (ByteArrayInputStream input = new ByteArrayInputStream(gzippedBytes);
             GZIPInputStream gzip = new GZIPInputStream(input)) {
            return gzip.readAllBytes();
        }
    }
}
