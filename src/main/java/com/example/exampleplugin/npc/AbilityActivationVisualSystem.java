package com.example.exampleplugin.npc;

import com.example.exampleplugin.levels.LevelSessionManager;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AbilityActivationVisualSystem extends TickingSystem<EntityStore> {
    private static final int FIRST_PARTY_SLOT = 1;
    private static final int LAST_PARTY_SLOT = 3;

    private final ConcurrentHashMap<PartySlotKey, AppliedVisual> appliedBySlot = new ConcurrentHashMap<>();

    @Override
    public void tick(float dt, int systemIndex, @Nonnull Store<EntityStore> store) {
        long now = System.currentTimeMillis();
        for (HeroAbility ability : AbilityRegistry.getAllAbilities()) {
            ability.tick(store, now);
        }

        LevelSessionManager.ActiveRunSnapshot snapshot = LevelSessionManager.get().getSnapshot();
        UUID worldId = store.getExternalData().getWorld().getWorldConfig().getUuid();
        if (snapshot == null || snapshot.runWorldUuid() == null || !snapshot.runWorldUuid().equals(worldId)) {
            clearAll(store);
            return;
        }

        Set<PartySlotKey> seenKeys = new HashSet<>();
        for (PlayerRef playerRef : store.getExternalData().getWorld().getPlayerRefs()) {
            Ref<EntityStore> ownerRef = playerRef.getReference();
            if (ownerRef == null || !ownerRef.isValid() || ownerRef.getStore() != store) {
                continue;
            }

            for (int slot = FIRST_PARTY_SLOT; slot <= LAST_PARTY_SLOT; slot++) {
                PartySlotKey key = new PartySlotKey(playerRef.getUuid(), slot);
                seenKeys.add(key);
                updateSlot(store, ownerRef, key, slot);
            }
        }

        for (Map.Entry<PartySlotKey, AppliedVisual> entry : this.appliedBySlot.entrySet()) {
            if (seenKeys.contains(entry.getKey())) {
                continue;
            }
            removeAppliedVisual(store, entry.getValue());
            this.appliedBySlot.remove(entry.getKey());
        }
    }

    private void updateSlot(
        @Nonnull Store<EntityStore> store,
        @Nonnull Ref<EntityStore> ownerRef,
        @Nonnull PartySlotKey key,
        int slot
    ) {
        AppliedVisual previous = this.appliedBySlot.get(key);

        HeroAbility ability = resolveAbility(ownerRef, slot);
        String effectId = ability == null ? null : ability.getActivationEffectId();
        EntityEffect effect = effectId == null ? null : EntityEffect.getAssetMap().getAsset(effectId);
        Ref<EntityStore> desiredTarget = ability != null && effect != null && ability.isAbilityActive(slot)
            ? resolvePartySlotEntity(ownerRef, slot)
            : null;

        boolean sameTarget = previous != null && previous.targetRef.equals(desiredTarget);
        boolean sameEffect = previous != null && previous.effectId.equals(effectId);
        if (sameTarget && sameEffect) {
            return;
        }

        if (previous != null) {
            removeAppliedVisual(store, previous);
            this.appliedBySlot.remove(key);
        }

        if (desiredTarget != null && desiredTarget.isValid() && desiredTarget.getStore() == store && effect != null) {
            applyEffect(store, desiredTarget, effect);
            this.appliedBySlot.put(key, new AppliedVisual(desiredTarget, effectId));
        }
    }

    @Nullable
    private static HeroAbility resolveAbility(@Nonnull Ref<EntityStore> ownerRef, int slot) {
        Integer roleIndex = TrackedSummonStore.getPartyRoleIndex(ownerRef, slot);
        if (roleIndex == null) {
            return null;
        }
        String roleId = NPCPlugin.get().getName(roleIndex);
        return AbilityRegistry.getAbility(roleId);
    }

    @Nullable
    private static Ref<EntityStore> resolvePartySlotEntity(@Nonnull Ref<EntityStore> ownerRef, int slot) {
        Ref<EntityStore> playerRef = TrackedSummonStore.getPlayerForPartySlot(ownerRef, slot);
        return playerRef != null ? playerRef : TrackedSummonStore.getNpcForUtilitySlot(ownerRef, slot);
    }

    private void clearAll(@Nonnull Store<EntityStore> store) {
        for (AppliedVisual applied : this.appliedBySlot.values()) {
            removeAppliedVisual(store, applied);
        }
        this.appliedBySlot.clear();
    }

    private static void applyEffect(
        @Nonnull Store<EntityStore> store,
        @Nonnull Ref<EntityStore> targetRef,
        @Nonnull EntityEffect effect
    ) {
        EffectControllerComponent effects = store.getComponent(targetRef, EffectControllerComponent.getComponentType());
        if (effects == null) {
            effects = new EffectControllerComponent();
        }

        effects.addEffect(targetRef, effect, store);
        store.putComponent(targetRef, EffectControllerComponent.getComponentType(), effects);
    }

    private static void removeAppliedVisual(@Nonnull Store<EntityStore> store, @Nonnull AppliedVisual applied) {
        EntityEffect effect = EntityEffect.getAssetMap().getAsset(applied.effectId);
        if (effect == null) {
            return;
        }
        removeEffect(store, applied.targetRef, effect);
    }

    private static void removeEffect(
        @Nonnull Store<EntityStore> store,
        @Nullable Ref<EntityStore> targetRef,
        @Nonnull EntityEffect effect
    ) {
        if (targetRef == null || !targetRef.isValid() || targetRef.getStore() != store) {
            return;
        }
        EffectControllerComponent effects = store.getComponent(targetRef, EffectControllerComponent.getComponentType());
        int effectIndex = EntityEffect.getAssetMap().getIndex(effect.getId());
        if (effects == null || effectIndex == Integer.MIN_VALUE || !effects.hasEffect(effectIndex)) {
            return;
        }

        effects.removeEffect(targetRef, effectIndex, store);
        store.putComponent(targetRef, EffectControllerComponent.getComponentType(), effects);
    }

    private record PartySlotKey(@Nonnull UUID playerUuid, int slot) {
    }

    private record AppliedVisual(@Nonnull Ref<EntityStore> targetRef, @Nonnull String effectId) {
    }
}
