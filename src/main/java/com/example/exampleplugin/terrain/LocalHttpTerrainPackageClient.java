package com.example.exampleplugin.terrain;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public final class LocalHttpTerrainPackageClient implements TerrainPackageClient {
    private static final Gson GSON = new Gson();

    private final HttpClient httpClient;
    private final URI pythonEndpoint;
    private final Duration requestTimeout;
    private final Duration downloadTimeout;

    public LocalHttpTerrainPackageClient(@Nonnull URI pythonEndpoint, @Nonnull Duration requestTimeout, @Nonnull Duration downloadTimeout) {
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(requestTimeout)
            .version(HttpClient.Version.HTTP_1_1)
            .build();
        this.pythonEndpoint = pythonEndpoint;
        this.requestTimeout = requestTimeout;
        this.downloadTimeout = downloadTimeout;
    }

    @Nonnull
    @Override
    public TerrainPackageDescriptor requestPackage(@Nonnull TerrainGenerationRequest request) throws IOException, InterruptedException {
        URI generateUri = pythonEndpoint.resolve("/generate");
        String requestJson = GSON.toJson(toServerPayload(request));
        HttpRequest httpRequest = HttpRequest.newBuilder(generateUri)
            .timeout(requestTimeout)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestJson))
            .build();
        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        ensureSuccess(response.statusCode(), "terrain package request", generateUri, response.body());

        JsonObject json = GSON.fromJson(response.body(), JsonObject.class);
        if (json == null) {
            throw new IOException("Terrain service returned an empty response body");
        }
        String jobId = firstPresent(json, "job_id", "jobId");
        if (jobId == null || jobId.isBlank()) {
            throw new IOException("Terrain service response missing job_id");
        }
        URI packageRoot = pythonEndpoint.resolve("/package/" + jobId + "/");
        return new TerrainPackageDescriptor(jobId, packageRoot);
    }

    @Nonnull
    private static Map<String, Object> toServerPayload(@Nonnull TerrainGenerationRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("prompt", request.prompt());
        payload.put("seed", Integer.toUnsignedLong(request.seed()));
        payload.put("originX", request.placementOriginX());
        payload.put("originZ", request.placementOriginZ());
        payload.put("baseY", request.baseY());
        payload.put("seaLevel", AiTerrainSettings.DEFAULT_SEA_LEVEL);
        payload.put("decorations", Boolean.TRUE);
        payload.put("smooth", Boolean.TRUE);
        payload.put("feature_smoothing", Boolean.TRUE);
        payload.put("smooth_strength", 0.3f);
        payload.put("cfg_scale", 5.0f);
        payload.put("num_steps", 50);
        payload.put("grid_size", request.gridSize());
        payload.put("overlap", request.overlap());
        payload.put("img2img_strength", request.img2imgStrength());
        return payload;
    }

    @Nonnull
    @Override
    public byte[] fetchBytes(@Nonnull URI uri) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
            .timeout(downloadTimeout)
            .GET()
            .build();
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        ensureSuccess(response.statusCode(), "terrain package download", uri, summarizeBody(response.body()));
        return response.body();
    }

    @Override
    public void deletePackage(@Nonnull TerrainPackageDescriptor descriptor) throws IOException, InterruptedException {
        URI uri = pythonEndpoint.resolve("/package/" + descriptor.jobId());
        HttpRequest request = HttpRequest.newBuilder(uri)
            .timeout(downloadTimeout)
            .DELETE()
            .build();
        HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        ensureSuccess(response.statusCode(), "terrain package delete", uri, null);
    }

    private static void ensureSuccess(int statusCode, @Nonnull String action, @Nonnull URI uri, String responseBody) throws IOException {
        if (statusCode >= 200 && statusCode < 300) {
            return;
        }
        String suffix = responseBody == null || responseBody.isBlank() ? "" : " body=" + responseBody;
        throw new IOException("HTTP " + statusCode + " during " + action + ": " + uri + suffix);
    }

    @Nonnull
    private static String summarizeBody(byte[] body) {
        if (body.length == 0) {
            return "";
        }
        int length = Math.min(body.length, 256);
        return new String(body, 0, length, StandardCharsets.UTF_8);
    }

    private static String stringOrNull(@Nonnull JsonObject json, @Nonnull String key) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : null;
    }

    private static String firstPresent(@Nonnull JsonObject json, @Nonnull String firstKey, @Nonnull String secondKey) {
        String first = stringOrNull(json, firstKey);
        return first != null ? first : stringOrNull(json, secondKey);
    }
}
