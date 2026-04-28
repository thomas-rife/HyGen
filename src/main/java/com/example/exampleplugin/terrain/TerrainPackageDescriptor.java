package com.example.exampleplugin.terrain;

import javax.annotation.Nonnull;
import java.net.URI;

public record TerrainPackageDescriptor(
    @Nonnull String jobId,
    @Nonnull URI packageRoot
) {
    @Nonnull
    public URI metadataUri() {
        return packageRoot.resolve("metadata.json");
    }

    @Nonnull
    public URI heightmapUri() {
        return packageRoot.resolve("heightmap.bin.gz");
    }

    @Nonnull
    public URI materialmapUri() {
        return packageRoot.resolve("materialmap.bin.gz");
    }

    @Nonnull
    public URI watermapUri() {
        return packageRoot.resolve("watermap.bin.gz");
    }

    @Nonnull
    public URI waterheightUri() {
        return packageRoot.resolve("waterheight.bin.gz");
    }

    @Nonnull
    public URI decorationsUri() {
        return packageRoot.resolve("decorations.json.gz");
    }
}
