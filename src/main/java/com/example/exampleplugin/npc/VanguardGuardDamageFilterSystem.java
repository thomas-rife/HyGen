package com.example.exampleplugin.npc;

import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.SystemGroup;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class VanguardGuardDamageFilterSystem extends DamageEventSystem {
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
        if (damage.isCancelled()) {
            return;
        }
        if (hasGuardEffect(archetypeChunk, index) || VanguardGuardAbility.isActiveForTarget(store, archetypeChunk.getReferenceTo(index))) {
            damage.setAmount(damage.getAmount() * VanguardGuardAbility.DAMAGE_MULTIPLIER);
        }
    }

    private static boolean hasGuardEffect(@Nonnull ArchetypeChunk<EntityStore> archetypeChunk, int index) {
        EffectControllerComponent effects = archetypeChunk.getComponent(index, EffectControllerComponent.getComponentType());
        int effectIndex = EntityEffect.getAssetMap().getIndex(VanguardGuardAbility.EFFECT_ID);
        return effects != null && effectIndex != Integer.MIN_VALUE && effects.hasEffect(effectIndex);
    }
}
