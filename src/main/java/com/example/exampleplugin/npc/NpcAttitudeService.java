package com.example.exampleplugin.npc;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.asset.type.attitude.Attitude;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.WorldSupport;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

import javax.annotation.Nonnull;
import java.lang.reflect.Field;
import java.util.List;

public final class NpcAttitudeService {
    private static final double LONG_OVERRIDE_SECONDS = 60.0 * 60.0 * 24.0;
    private static final Field ATTITUDE_OVERRIDE_MEMORY_FIELD = resolveOverrideMemoryField();

    private NpcAttitudeService() {
    }

    public static void configureCompanionAttitudes(
        @Nonnull Store<EntityStore> store,
        @Nonnull World world,
        @Nonnull Ref<EntityStore> ownerRef,
        @Nonnull Ref<EntityStore> npcRef
    ) {
        WorldSupport support = getWorldSupport(store, npcRef);
        if (support == null) {
            return;
        }

        safeOverride(support, ownerRef);
        for (PlayerRef playerRef : world.getPlayerRefs()) {
            Ref<EntityStore> playerEntityRef = playerRef.getReference();
            if (playerEntityRef != null && playerEntityRef.isValid()) {
                safeOverride(support, playerEntityRef);
            }
        }

        List<Ref<EntityStore>> tracked = TrackedSummonStore.getTrackedSnapshot(ownerRef);
        for (Ref<EntityStore> allyRef : tracked) {
            if (allyRef == null || !allyRef.isValid() || allyRef == npcRef) {
                continue;
            }

            safeOverride(support, allyRef);

            WorldSupport allySupport = getWorldSupport(store, allyRef);
            if (allySupport != null) {
                safeOverride(allySupport, npcRef);
            }
        }
    }

    private static void safeOverride(@Nonnull WorldSupport support, @Nonnull Ref<EntityStore> targetRef) {
        try {
            ensureOverrideMemory(support);
            support.overrideAttitude(targetRef, Attitude.FRIENDLY, LONG_OVERRIDE_SECONDS);
        } catch (RuntimeException ignored) {
            // Some roles do not allocate override memory; ignore and fall back to damage filtering.
        }
    }

    private static void ensureOverrideMemory(@Nonnull WorldSupport support) {
        if (ATTITUDE_OVERRIDE_MEMORY_FIELD == null) {
            return;
        }
        try {
            Object value = ATTITUDE_OVERRIDE_MEMORY_FIELD.get(support);
            if (value == null) {
                ATTITUDE_OVERRIDE_MEMORY_FIELD.set(support, new Int2ObjectOpenHashMap<>());
            }
        } catch (IllegalAccessException ignored) {
            // Keep running even if reflection is restricted.
        }
    }

    private static Field resolveOverrideMemoryField() {
        try {
            Field field = WorldSupport.class.getDeclaredField("attitudeOverrideMemory");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static WorldSupport getWorldSupport(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> npcRef) {
        NPCEntity npcEntity = store.getComponent(npcRef, NPCEntity.getComponentType());
        if (npcEntity == null) {
            return null;
        }

        Role role = npcEntity.getRole();
        if (role == null) {
            return null;
        }

        return role.getWorldSupport();
    }
}
