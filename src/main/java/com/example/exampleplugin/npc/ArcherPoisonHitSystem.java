package com.example.exampleplugin.npc;

import com.example.exampleplugin.levels.LevelRunCombatStore;
import com.example.exampleplugin.levels.LevelSessionManager;
import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.SystemGroup;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.UUID;

public class ArcherPoisonHitSystem extends DamageEventSystem {
    @Nullable
    @Override
    public SystemGroup<EntityStore> getGroup() {
        return DamageModule.get().getInspectDamageGroup();
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
        if (damage.isCancelled()) {
            return;
        }
        if (!(damage.getSource() instanceof Damage.EntitySource entitySource)) {
            return;
        }
        Ref<EntityStore> sourceRef = entitySource.getRef();
        if (!CombatTargetingUtil.isAlive(store, sourceRef)) {
            return;
        }

        Integer slot = TrackedSummonStore.getActivePartySlot(sourceRef);
        if (slot != null && ArcherPoisonAbility.isArcherSlot(sourceRef, slot)) {
            if (ArcherPoisonAbility.hasLoadedArrow(slot)) {
                Ref<EntityStore> targetRef = archetypeChunk.getReferenceTo(index);
                if (CombatTargetingUtil.isAlive(store, targetRef)) {
                    ArcherPoisonAbility.consumeLoadedArrow(slot);
                    damage.setAmount(damage.getAmount() * 8.0f);
                }
            }
        }
    }
}
