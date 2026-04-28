package com.example.exampleplugin.levels;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class LevelRunCombatStore {
    private static final LevelRunCombatStore INSTANCE = new LevelRunCombatStore();

    private final ConcurrentHashMap<UUID, CombatWorldState> states = new ConcurrentHashMap<>();

    private LevelRunCombatStore() {
    }

    @Nonnull
    public static LevelRunCombatStore get() {
        return INSTANCE;
    }

    public void clearAll() {
        this.states.clear();
    }

    public void clearWorld(@Nonnull UUID worldId) {
        this.states.remove(worldId);
    }

    public void updateWorld(
        @Nonnull UUID worldId,
        @Nonnull UUID ownerPlayerUuid,
        @Nonnull List<UUID> participantPlayerUuids,
        @Nonnull List<Ref<EntityStore>> activeEnemies
    ) {
        List<Ref<EntityStore>> copy = new ArrayList<>(activeEnemies.size());
        for (Ref<EntityStore> ref : activeEnemies) {
            if (ref != null && ref.isValid()) {
                copy.add(ref);
            }
        }
        this.states.put(worldId, new CombatWorldState(ownerPlayerUuid, List.copyOf(participantPlayerUuids), copy));
    }

    @Nullable
    public CombatWorldState getWorld(@Nonnull UUID worldId) {
        return this.states.get(worldId);
    }

    public record CombatWorldState(
        @Nonnull UUID ownerPlayerUuid,
        @Nonnull List<UUID> participantPlayerUuids,
        @Nonnull List<Ref<EntityStore>> activeEnemies
    ) {
    }
}
