package com.example.exampleplugin.npc;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class VanguardGuardVisualSystem extends TickingSystem<EntityStore> {
    private static final HytaleLogger LOGGER = HytaleLogger.get("Battleheart|VanguardGuard");
    private static final int FIRST_PARTY_SLOT = 1;
    private static final int LAST_PARTY_SLOT = 3;
    private static final String ACTIVE_HERO_EFFECT_ID = "HyGen_Active_Hero";
    private static final long MISSING_EFFECT_LOG_INTERVAL_MS = 5000L;

    private final ConcurrentHashMap<Integer, Ref<EntityStore>> appliedBySlot = new ConcurrentHashMap<>();
    private long lastMissingEffectLogMs;

    @Override
    public void tick(float dt, int systemIndex, @Nonnull Store<EntityStore> store) {
        VanguardGuardAbility.cleanupExpired();
        clearActiveHeroTint(store);
        EntityEffect effect = EntityEffect.getAssetMap().getAsset(VanguardGuardAbility.EFFECT_ID);
        if (effect == null) {
            long now = System.currentTimeMillis();
            if (now - this.lastMissingEffectLogMs >= MISSING_EFFECT_LOG_INTERVAL_MS) {
                this.lastMissingEffectLogMs = now;
                LOGGER.at(Level.WARNING).log("Vanguard guard tint asset '%s' is not loaded.", VanguardGuardAbility.EFFECT_ID);
            }
            return;
        }

        Ref<EntityStore> ownerRef = VanguardGuardAbility.getOwnerRefForCurrentRun(store);
        if (ownerRef == null) {
            clearAll(store, effect);
            return;
        }

        for (int slot = FIRST_PARTY_SLOT; slot <= LAST_PARTY_SLOT; slot++) {
            Ref<EntityStore> desired = VanguardGuardAbility.isActiveForSlot(slot) && VanguardGuardAbility.isVanguardSlot(ownerRef, slot)
                ? VanguardGuardAbility.getPartySlotEntity(ownerRef, slot)
                : null;
            updateSlot(store, slot, desired, effect);
        }
    }

    private static void clearActiveHeroTint(@Nonnull Store<EntityStore> store) {
        EntityEffect activeHeroEffect = EntityEffect.getAssetMap().getAsset(ACTIVE_HERO_EFFECT_ID);
        if (activeHeroEffect == null) {
            return;
        }
        for (PlayerRef playerRef : store.getExternalData().getWorld().getPlayerRefs()) {
            Ref<EntityStore> ref = playerRef.getReference();
            removeEffect(store, ref, activeHeroEffect);

            List<Ref<EntityStore>> companions = TrackedSummonStore.getTrackedSnapshot(ref);
            for (Ref<EntityStore> companion : companions) {
                removeEffect(store, companion, activeHeroEffect);
            }
        }
    }

    private void clearAll(@Nonnull Store<EntityStore> store, @Nonnull EntityEffect effect) {
        for (Ref<EntityStore> previous : this.appliedBySlot.values()) {
            removeEffect(store, previous, effect);
        }
        this.appliedBySlot.clear();
    }

    private void updateSlot(
        @Nonnull Store<EntityStore> store,
        int slot,
        @Nullable Ref<EntityStore> desired,
        @Nonnull EntityEffect effect
    ) {
        Ref<EntityStore> previous = this.appliedBySlot.get(slot);
        if (previous != null && previous.equals(desired)) {
            return;
        }

        if (previous != null) {
            removeEffect(store, previous, effect);
            this.appliedBySlot.remove(slot);
        }

        if (desired != null && desired.isValid() && desired.getStore() == store) {
            applyEffect(store, desired, effect);
            this.appliedBySlot.put(slot, desired);
        }
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
}
