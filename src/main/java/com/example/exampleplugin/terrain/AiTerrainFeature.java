package com.example.exampleplugin.terrain;

import com.hypixel.hytale.codec.lookup.Priority;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.universe.world.worldgen.provider.IWorldGenProvider;

import javax.annotation.Nonnull;

public final class AiTerrainFeature {
    private AiTerrainFeature() {
    }

    public static void install(@Nonnull JavaPlugin plugin) {
        IWorldGenProvider.CODEC.register(
            Priority.DEFAULT,
            AiVoidWorldGenProvider.ID,
            AiVoidWorldGenProvider.class,
            AiVoidWorldGenProvider.CODEC
        );
        AiTerrainService service = AiTerrainService.get();
        plugin.getCommandRegistry().registerCommand(new AiWorldGenCommand(service));
    }

    public static void shutdown() {
        AiTerrainService.get().close();
    }
}
