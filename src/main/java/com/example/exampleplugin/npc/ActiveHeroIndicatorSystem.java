package com.example.exampleplugin.npc;

import com.example.exampleplugin.levels.LevelSessionManager;
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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class ActiveHeroIndicatorSystem extends TickingSystem<EntityStore> {
    private static final HytaleLogger LOGGER = HytaleLogger.get("Battleheart|ActiveHero");
    private static final String ACTIVE_EFFECT_ID = "HyGen_Active_Hero";
    private static final long MISSING_EFFECT_LOG_INTERVAL_MS = 5000L;

    private final ConcurrentHashMap<Ref<EntityStore>, Ref<EntityStore>> activeIndicatorByPlayer = new ConcurrentHashMap<>();
    private long lastMissingEffectLogMs;

    @Override
    public void tick(float dt, int systemIndex, @Nonnull Store<EntityStore> store) {
        long now = System.currentTimeMillis();
        EntityEffect activeEffect = EntityEffect.getAssetMap().getAsset(ACTIVE_EFFECT_ID);
        if (activeEffect == null) {
            if (now - this.lastMissingEffectLogMs >= MISSING_EFFECT_LOG_INTERVAL_MS) {
                this.lastMissingEffectLogMs = now;
                LOGGER.at(Level.WARNING).log("Active hero tint asset '%s' is not loaded.", ACTIVE_EFFECT_ID);
            }
            return;
        }

        LevelSessionManager.ActiveRunSnapshot snapshot = LevelSessionManager.get().getSnapshot();
        UUID runWorldId = snapshot == null ? null : snapshot.runWorldUuid();
        UUID worldId = store.getExternalData().getWorld().getWorldConfig().getUuid();
        boolean activeRunWorld = runWorldId != null && runWorldId.equals(worldId);

        for (PlayerRef playerRef : store.getExternalData().getWorld().getPlayerRefs()) {
            Ref<EntityStore> playerEntityRef = playerRef.getReference();
            if (playerEntityRef == null || !playerEntityRef.isValid() || playerEntityRef.getStore() != store) {
                continue;
            }

            Ref<EntityStore> desiredIndicator = activeRunWorld && TrackedSummonStore.getActivePartySlot(playerEntityRef) != null
                ? playerEntityRef
                : null;
            updateIndicator(store, playerEntityRef, desiredIndicator, activeEffect);
        }
    }

    private void updateIndicator(
        @Nonnull Store<EntityStore> store,
        @Nonnull Ref<EntityStore> playerRef,
        @Nullable Ref<EntityStore> desiredIndicator,
        @Nonnull EntityEffect activeEffect
    ) {
        Ref<EntityStore> previousIndicator = this.activeIndicatorByPlayer.get(playerRef);
        if (previousIndicator != null && previousIndicator.equals(desiredIndicator)) {
            return;
        }

        if (previousIndicator != null) {
            removeEffect(store, previousIndicator, activeEffect);
            this.activeIndicatorByPlayer.remove(playerRef);
        }

        if (desiredIndicator != null) {
            this.activeIndicatorByPlayer.put(playerRef, desiredIndicator);
            applyEffect(store, desiredIndicator, activeEffect);
        }
    }

    private static void applyEffect(
        @Nonnull Store<EntityStore> store,
        @Nullable Ref<EntityStore> targetRef,
        @Nonnull EntityEffect effect
    ) {
        if (targetRef == null || !targetRef.isValid() || targetRef.getStore() != store) {
            return;
        }
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
