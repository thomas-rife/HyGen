package com.example.exampleplugin.npc;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.SystemGroup;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.knockback.KnockbackComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class CompanionPlayerDamageFilterSystem extends DamageEventSystem {
    @Nullable
    @Override
    public SystemGroup<EntityStore> getGroup() {
        return DamageModule.get().getFilterDamageGroup();
    }

    @Nullable
    @Override
    public Query<EntityStore> getQuery() {
        return Archetype.empty();
    }

    @Override
    public void handle(
        int index,
        @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
        @Nonnull Store<EntityStore> store,
        @Nonnull CommandBuffer<EntityStore> commandBuffer,
        @Nonnull Damage damage
    ) {
        if (!(damage.getSource() instanceof Damage.EntitySource entitySource)) {
            return;
        }

        Ref<EntityStore> sourceRef = entitySource.getRef();
        if (!isLocalRef(store, sourceRef)) {
            return;
        }

        Ref<EntityStore> targetRef = archetypeChunk.getReferenceTo(index);
        if (!isLocalRef(store, targetRef)) {
            return;
        }

        boolean targetIsPlayer = archetypeChunk.getComponent(index, Player.getComponentType()) != null;
        if (!CombatTargetingUtil.areAlliedInRun(store, sourceRef, targetRef)) {
            if (!targetIsPlayer || !TrackedSummonStore.isTrackedNpc(sourceRef)) {
                return;
            }
        }

        damage.setCancelled(true);
        commandBuffer.tryRemoveComponent(targetRef, KnockbackComponent.getComponentType());
    }

    private static boolean isLocalRef(@Nonnull Store<EntityStore> store, @Nullable Ref<EntityStore> ref) {
        return ref != null && ref.isValid() && ref.getStore() == store;
    }
}
