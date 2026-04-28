package com.example.exampleplugin.npc;

import com.example.exampleplugin.levels.LevelRunCombatStore;
import com.example.exampleplugin.levels.LevelSessionManager;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.shape.Box;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.math.vector.Vector2d;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.modules.collision.CollisionMath;
import com.hypixel.hytale.server.core.modules.entity.component.BoundingBox;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.TargetUtil;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;
import java.util.logging.Level;

public class FocusTargetHighlightSystem extends TickingSystem<EntityStore> {
    private static final HytaleLogger LOGGER = HytaleLogger.get("Battleheart|FocusTint");
    private static final String FOCUS_EFFECT_ID = "HyGen_Focus_Target";
    private static final String MAPPED_EFFECT_ID = "HyGen_Mapped_Target";
    private static final String HEAL_FOCUS_EFFECT_ID = "HyGen_Focus_Heal_Target";
    private static final String HEAL_MAPPED_EFFECT_ID = "HyGen_Mapped_Heal_Target";
    private static final float FOCUS_RANGE = 512.0f;
    private static final long MISSING_EFFECT_LOG_INTERVAL_MS = 5000L;
    
    // Set this to true to disable all entity highlights (yellow/green tints)
    private static final boolean DISABLE_HIGHLIGHTS = true;

    private final ConcurrentHashMap<Ref<EntityStore>, HighlightState> highlightByPlayer = new ConcurrentHashMap<>();
    private long lastMissingEffectLogMs;

    @Override
    public void tick(float dt, int systemIndex, @Nonnull Store<EntityStore> store) {
        if (DISABLE_HIGHLIGHTS) {
            if (!this.highlightByPlayer.isEmpty()) {
                clearHighlightsForStore(store);
                this.highlightByPlayer.clear();
            }
            return;
        }
        long now = System.currentTimeMillis();
        EntityEffect focusEffect = EntityEffect.getAssetMap().getAsset(FOCUS_EFFECT_ID);
        EntityEffect mappedEffect = EntityEffect.getAssetMap().getAsset(MAPPED_EFFECT_ID);
        EntityEffect healFocusEffect = EntityEffect.getAssetMap().getAsset(HEAL_FOCUS_EFFECT_ID);
        EntityEffect healMappedEffect = EntityEffect.getAssetMap().getAsset(HEAL_MAPPED_EFFECT_ID);
        if (focusEffect == null || mappedEffect == null || healFocusEffect == null || healMappedEffect == null) {
            if (now - this.lastMissingEffectLogMs >= MISSING_EFFECT_LOG_INTERVAL_MS) {
                this.lastMissingEffectLogMs = now;
                LOGGER.at(Level.WARNING)
                    .log(
                        "Focus tint assets loaded? soft=%s mapped=%s healSoft=%s healMapped=%s",
                        focusEffect != null,
                        mappedEffect != null,
                        healFocusEffect != null,
                        healMappedEffect != null
                    );
            }
            return;
        }

        LevelSessionManager.ActiveRunSnapshot snapshot = LevelSessionManager.get().getSnapshot();
        if (snapshot == null || snapshot.runWorldUuid() == null) {
            clearHighlightsForStore(store);
            return;
        }
        UUID worldId = store.getExternalData().getWorld().getWorldConfig().getUuid();
        if (!snapshot.runWorldUuid().equals(worldId)) {
            clearHighlightsForStore(store);
            return;
        }

        LevelRunCombatStore.CombatWorldState combatState = LevelRunCombatStore.get().getWorld(worldId);
        if (combatState == null) {
            clearHighlightsForStore(store);
            return;
        }

        for (PlayerRef playerRef : store.getExternalData().getWorld().getPlayerRefs()) {
            Ref<EntityStore> playerEntityRef = playerRef.getReference();
            if (playerEntityRef == null || !playerEntityRef.isValid() || playerEntityRef.getStore() != store) {
                continue;
            }

            boolean isHealer = CombatTargetingUtil.isActiveHeroHealer(store, playerEntityRef);
            Ref<EntityStore> mappedTarget = TrackedSummonStore.getTargetForActivePartySlot(playerEntityRef, store);
            
            // Re-validate existing target if role swapped
            if (mappedTarget != null && !isValidTarget(store, playerEntityRef, mappedTarget, isHealer)) {
                TrackedSummonStore.clearTargetForActivePartySlot(playerEntityRef, store);
                mappedTarget = null;
            }

            List<Ref<EntityStore>> potentialTargets = new ArrayList<>();
            if (isHealer) {
                for (UUID ownerUuid : combatState.participantPlayerUuids()) {
                    PlayerRef owner = Universe.get().getPlayer(ownerUuid);
                    if (owner != null && owner.getReference() != null) {
                        potentialTargets.add(owner.getReference());
                        potentialTargets.addAll(TrackedSummonStore.getTrackedSnapshot(owner.getReference()));
                    }
                }
            } else {
                potentialTargets.addAll(combatState.activeEnemies());
            }

            Ref<EntityStore> focusTarget = chooseVisibleTargetUnderCrosshair(store, playerEntityRef, potentialTargets);
            if (focusTarget != null && focusTarget.equals(mappedTarget)) {
                focusTarget = null;
            }
            EntityEffect playerFocusEffect = isHealer ? healFocusEffect : focusEffect;
            EntityEffect playerMappedEffect = isHealer ? healMappedEffect : mappedEffect;
            updatePlayerHighlights(
                store,
                playerEntityRef,
                potentialTargets,
                focusTarget,
                mappedTarget,
                playerFocusEffect,
                playerMappedEffect
            );
        }
    }

    private static boolean isValidTarget(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> playerRef, @Nonnull Ref<EntityStore> targetRef, boolean isHealer) {
        if (!CombatTargetingUtil.isAlive(store, targetRef)) {
            return false;
        }
        if (isHealer) {
            return CombatTargetingUtil.areAlliedInRun(store, playerRef, targetRef);
        } else {
            return CombatTargetingUtil.isLiveEnemyTarget(store, targetRef);
        }
    }

    private void clearHighlightsForStore(@Nonnull Store<EntityStore> store) {
        for (HighlightState state : this.highlightByPlayer.values()) {
            removeTintById(store, state.focusTarget, state.focusEffectId);
            removeTintById(store, state.mappedTarget, state.mappedEffectId);
            state.focusTarget = null;
            state.mappedTarget = null;
            state.focusEffectId = null;
            state.mappedEffectId = null;
        }
    }

    private void updatePlayerHighlights(
        @Nonnull Store<EntityStore> store,
        @Nonnull Ref<EntityStore> playerRef,
        @Nonnull List<Ref<EntityStore>> activeEnemies,
        @Nullable Ref<EntityStore> focusTarget,
        @Nullable Ref<EntityStore> mappedTarget,
        @Nonnull EntityEffect focusEffect,
        @Nonnull EntityEffect mappedEffect
    ) {
        HighlightState state = this.highlightByPlayer.computeIfAbsent(playerRef, ignored -> new HighlightState());
        clearOrphanTints(store, activeEnemies, focusTarget, mappedTarget, focusEffect, mappedEffect);
        if (!Objects.equals(state.focusTarget, focusTarget) || !Objects.equals(state.focusEffectId, focusEffect.getId())) {
            removeTintById(store, state.focusTarget, state.focusEffectId);
            state.focusTarget = focusTarget;
            state.focusEffectId = focusTarget == null ? null : focusEffect.getId();
            applyTint(store, state.focusTarget, focusEffect);
        }
        if (!Objects.equals(state.mappedTarget, mappedTarget) || !Objects.equals(state.mappedEffectId, mappedEffect.getId())) {
            removeTintById(store, state.mappedTarget, state.mappedEffectId);
            state.mappedTarget = mappedTarget;
            state.mappedEffectId = mappedTarget == null ? null : mappedEffect.getId();
            applyTint(store, state.mappedTarget, mappedEffect);
        }
    }

    private static void clearOrphanTints(
        @Nonnull Store<EntityStore> store,
        @Nonnull List<Ref<EntityStore>> activeEnemies,
        @Nullable Ref<EntityStore> focusTarget,
        @Nullable Ref<EntityStore> mappedTarget,
        @Nonnull EntityEffect focusEffect,
        @Nonnull EntityEffect mappedEffect
    ) {
        for (Ref<EntityStore> enemy : activeEnemies) {
            if (enemy == null || !enemy.isValid() || enemy.getStore() != store) {
                continue;
            }
            clearHighlightEffects(store, enemy, enemy.equals(focusTarget), enemy.equals(mappedTarget), focusEffect, mappedEffect);
        }
    }

    @Nullable
    public static Ref<EntityStore> chooseVisibleTargetUnderCrosshair(
        @Nonnull Store<EntityStore> store,
        @Nonnull Ref<EntityStore> playerRef,
        @Nonnull List<Ref<EntityStore>> targets
    ) {
        Transform look = TargetUtil.getLook(playerRef, store);
        Vector3d rayStart = look.getPosition();
        Vector3d rayDirection = look.getDirection();
        if (rayDirection.squaredLength() <= 0.000001) {
            return null;
        }

        Vector3d blockingHit = TargetUtil.getTargetLocation(look, blockId -> blockId != 0, FOCUS_RANGE, store);
        double blockingDistance = blockingHit == null ? FOCUS_RANGE : rayStart.distanceTo(blockingHit);
        Ref<EntityStore> best = null;
        double bestScore = Double.MAX_VALUE;

        for (Ref<EntityStore> target : targets) {
            if (!CombatTargetingUtil.isAlive(store, target)) {
                continue;
            }

            double entityFocusDistance = getEntityHitDistance(store, target, rayStart, rayDirection);
            if (entityFocusDistance < 0.0 || entityFocusDistance > FOCUS_RANGE) {
                continue;
            }
            if (entityFocusDistance > blockingDistance + 0.05) {
                continue;
            }

            if (entityFocusDistance < bestScore) {
                bestScore = entityFocusDistance;
                best = target;
            }
        }

        return best;
    }

    private static double getEntityHitDistance(
        @Nonnull Store<EntityStore> store,
        @Nonnull Ref<EntityStore> targetRef,
        @Nonnull Vector3d rayStart,
        @Nonnull Vector3d rayDirection
    ) {
        BoundingBox boundingBoxComponent = store.getComponent(targetRef, BoundingBox.getComponentType());
        TransformComponent transformComponent = store.getComponent(targetRef, TransformComponent.getComponentType());
        if (boundingBoxComponent == null || transformComponent == null) {
            return -1.0;
        }

        Box boundingBox = boundingBoxComponent.getBoundingBox();
        Vector3d position = transformComponent.getPosition();
        Vector2d minMax = new Vector2d();
        if (!CollisionMath.intersectRayAABB(
            rayStart,
            rayDirection,
            position.getX(),
            position.getY(),
            position.getZ(),
            boundingBox,
            minMax
        )) {
            return -1.0;
        }
        return Math.max(0.0, minMax.getX());
    }

    private static void applyTint(
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

        boolean applied = effects.addEffect(targetRef, effect, store);
        if (!applied) {
            LOGGER.at(Level.WARNING).log("Failed to apply focus tint effect '%s' to %s.", effect.getId(), targetRef);
        }
        store.putComponent(targetRef, EffectControllerComponent.getComponentType(), effects);
    }

    private static void removeTint(
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

    private static void clearHighlightEffects(
        @Nonnull Store<EntityStore> store,
        @Nonnull Ref<EntityStore> targetRef,
        boolean keepFocusEffect,
        boolean keepMappedEffect,
        @Nonnull EntityEffect activeFocusEffect,
        @Nonnull EntityEffect activeMappedEffect
    ) {
        if (!keepFocusEffect) {
            removeTintById(store, targetRef, FOCUS_EFFECT_ID);
            removeTintById(store, targetRef, HEAL_FOCUS_EFFECT_ID);
        } else {
            removeTintById(store, targetRef, activeFocusEffect.getId().equals(FOCUS_EFFECT_ID) ? HEAL_FOCUS_EFFECT_ID : FOCUS_EFFECT_ID);
        }

        if (!keepMappedEffect) {
            removeTintById(store, targetRef, MAPPED_EFFECT_ID);
            removeTintById(store, targetRef, HEAL_MAPPED_EFFECT_ID);
        } else {
            removeTintById(store, targetRef, activeMappedEffect.getId().equals(MAPPED_EFFECT_ID) ? HEAL_MAPPED_EFFECT_ID : MAPPED_EFFECT_ID);
        }
    }

    private static void removeTintById(
        @Nonnull Store<EntityStore> store,
        @Nullable Ref<EntityStore> targetRef,
        @Nullable String effectId
    ) {
        if (effectId == null || effectId.isBlank()) {
            return;
        }
        EntityEffect effect = EntityEffect.getAssetMap().getAsset(effectId);
        if (effect == null) {
            return;
        }
        removeTint(store, targetRef, effect);
    }

    private static final class HighlightState {
        @Nullable
        private Ref<EntityStore> focusTarget;
        @Nullable
        private Ref<EntityStore> mappedTarget;
        @Nullable
        private String focusEffectId;
        @Nullable
        private String mappedEffectId;
    }
}
