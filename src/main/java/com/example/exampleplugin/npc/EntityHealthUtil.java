package com.example.exampleplugin.npc;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

public final class EntityHealthUtil {
    private EntityHealthUtil() {
    }

    public static void fillHealthToMax(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        setHealthPercentOfMax(store, ref, 1.0f);
    }

    public static boolean applyDamage(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref, float amount) {
        if (amount <= 0f) {
            return false;
        }

        EntityStatMap stats = store.getComponent(ref, EntityStatMap.getComponentType());
        if (stats == null) {
            return false;
        }
        int healthIndex = DefaultEntityStatTypes.getHealth();
        if (healthIndex < 0) {
            return false;
        }
        EntityStatValue health = stats.get(healthIndex);
        if (health == null) {
            return false;
        }

        float current = health.get();
        if (current <= 0f) {
            return false;
        }

        stats.setStatValue(healthIndex, Math.max(0f, current - amount));
        return true;
    }

    public static float readHealthPercent(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        EntityStatMap stats = store.getComponent(ref, EntityStatMap.getComponentType());
        if (stats == null) {
            return 0f;
        }
        int healthIndex = DefaultEntityStatTypes.getHealth();
        if (healthIndex < 0) {
            return 0f;
        }
        EntityStatValue health = stats.get(healthIndex);
        if (health == null) {
            return 0f;
        }
        float max = health.getMax();
        if (max <= 0f) {
            return 0f;
        }
        return Math.max(0f, Math.min(1f, health.get() / max));
    }

    public static void setHealthPercentOfMax(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref, float percent) {
        EntityStatMap stats = store.getComponent(ref, EntityStatMap.getComponentType());
        if (stats == null) {
            return;
        }
        int healthIndex = DefaultEntityStatTypes.getHealth();
        if (healthIndex < 0) {
            return;
        }
        EntityStatValue health = stats.get(healthIndex);
        if (health == null) {
            return;
        }
        float clampedPercent = Math.max(0f, Math.min(1f, percent));
        stats.setStatValue(healthIndex, health.getMax() * clampedPercent);
    }
}
