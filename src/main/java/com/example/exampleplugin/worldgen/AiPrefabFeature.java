package com.example.exampleplugin.worldgen;

import com.hypixel.hytale.codec.lookup.Priority;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.universe.world.worldgen.provider.IWorldGenProvider;

import javax.annotation.Nonnull;

public final class AiPrefabFeature {
    private AiPrefabFeature() {
    }

    public static void install(@Nonnull JavaPlugin plugin) {
        AiPrefabStore.get().ensureConfig();
        IWorldGenProvider.CODEC.register(
            Priority.DEFAULT,
            AiPrefabWorldGenProvider.ID,
            AiPrefabWorldGenProvider.class,
            AiPrefabWorldGenProvider.CODEC
        );
        plugin.getCommandRegistry().registerCommand(new AiPrefabCommand());
    }
}
