package com.example.exampleplugin.terrain;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

public interface TerrainPackageClient extends AutoCloseable {
    @Nonnull
    TerrainPackageDescriptor requestPackage(@Nonnull TerrainGenerationRequest request) throws IOException, InterruptedException;

    @Nonnull
    byte[] fetchBytes(@Nonnull URI uri) throws IOException, InterruptedException;

    @Nonnull
    default String fetchUtf8(@Nonnull URI uri) throws IOException, InterruptedException {
        return new String(fetchBytes(uri), StandardCharsets.UTF_8);
    }

    void deletePackage(@Nonnull TerrainPackageDescriptor descriptor) throws IOException, InterruptedException;

    @Override
    default void close() {
    }
}
