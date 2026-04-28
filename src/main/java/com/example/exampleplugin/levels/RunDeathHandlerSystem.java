package com.example.exampleplugin.levels;

import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathSystems;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.universe.world.World;
import com.example.exampleplugin.npc.NpcSwapService;
import com.example.exampleplugin.npc.NpcCarrierRoleService;
import com.example.exampleplugin.npc.TrackedSummonStore;
import com.hypixel.hytale.server.core.universe.Universe;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public class RunDeathHandlerSystem extends DeathSystems.OnDeathSystem {
    private static final HytaleLogger LOGGER = HytaleLogger.get("Battleheart|RunDeath");
    private final Set<UUID> processing = ConcurrentHashMap.newKeySet();

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return Archetype.empty();
    }

    @Override
    public void onComponentAdded(
        @Nonnull Ref<EntityStore> ref,
        @Nonnull DeathComponent component,
        @Nonnull Store<EntityStore> store,
        @Nonnull CommandBuffer<EntityStore> commandBuffer
    ) {
        if (store.getComponent(ref, Player.getComponentType()) == null) {
            return;
        }

        PlayerRef deadPlayer = store.getComponent(ref, PlayerRef.getComponentType());
        if (deadPlayer == null) {
            return;
        }

        LevelSessionManager.ActiveRunSnapshot snapshot = LevelSessionManager.get().getSnapshot();
        if (snapshot == null || snapshot.runWorldUuid() == null) {
            return;
        }
        if (!snapshot.participantPlayerUuids().contains(deadPlayer.getUuid())) {
            return;
        }
        if (!snapshot.runWorldUuid().equals(store.getExternalData().getWorld().getWorldConfig().getUuid())) {
            return;
        }

        component.setShowDeathMenu(false);
        LOGGER.at(Level.INFO).log(
            "player death in active run player=%s world=%s tracked=%d",
            deadPlayer.getUuid(),
            snapshot.runWorldUuid(),
            TrackedSummonStore.getTrackedSnapshot(ref).size()
        );

        @Nullable Ref<EntityStore> survivor = chooseLivingCompanion(store, ref);
        if (survivor != null) {
            Integer previousSlot = TrackedSummonStore.getActivePartySlot(ref);
            Integer survivorSlot = TrackedSummonStore.getPartySlotForNpc(ref, survivor);
            Integer previousRoleIndex = previousSlot == null ? null : TrackedSummonStore.getPartyRoleIndex(ref, previousSlot);
            String failure = NpcSwapService.swapWithNpc(commandBuffer, ref, survivor);
            if (failure == null) {
                LOGGER.at(Level.INFO).log("falling back to living companion survivor=%s slot=%s", survivor, survivorSlot);
                commandBuffer.tryRemoveComponent(ref, DeathComponent.getComponentType());
                EntityStatMap playerStats = store.getComponent(ref, EntityStatMap.getComponentType());
                if (playerStats != null) {
                    playerStats.maximizeStatValue(DefaultEntityStatTypes.getHealth());
                    commandBuffer.putComponent(ref, EntityStatMap.getComponentType(), playerStats);
                }
                World world = store.getExternalData().getWorld();
                world.execute(() -> completeCompanionFallback(ref, survivor, survivorSlot, previousRoleIndex, deadPlayer));
                return;
            }
            LOGGER.at(Level.WARNING).log("companion fallback failed survivor=%s reason=%s", survivor, failure);
        }

        if (!this.processing.add(snapshot.runWorldUuid())) {
            return;
        }

        UUID deadPlayerUuid = deadPlayer.getUuid();
        commandBuffer.tryRemoveComponent(ref, DeathComponent.getComponentType());
        EntityStatMap playerStats = store.getComponent(ref, EntityStatMap.getComponentType());
        if (playerStats != null) {
            playerStats.maximizeStatValue(DefaultEntityStatTypes.getHealth());
            commandBuffer.putComponent(ref, EntityStatMap.getComponentType(), playerStats);
        }

        LOGGER.at(Level.INFO).log("no living companion remains; finishing defeated run world=%s", snapshot.runWorldUuid());
        CompletableFuture.runAsync(() -> finishDefeatedRun(snapshot.runWorldUuid(), deadPlayerUuid), CompletableFuture.delayedExecutor(500L, TimeUnit.MILLISECONDS));
    }

    private void finishDefeatedRun(@Nonnull UUID runWorldUuid, @Nonnull UUID deadPlayerUuid) {
        LevelSessionManager.get().finishDefeatedRunInPlace(deadPlayerUuid).whenComplete((result, throwable) -> {
            this.processing.remove(runWorldUuid);
            PlayerRef refreshedPlayer = Universe.get().getPlayer(deadPlayerUuid);
            if (refreshedPlayer == null) {
                return;
            }
            if (throwable != null) {
                String reason = throwable.getCause() != null ? throwable.getCause().getMessage() : throwable.getMessage();
                LOGGER.at(Level.WARNING).log("defeat cleanup failed world=%s reason=%s", runWorldUuid, reason);
                refreshedPlayer.sendMessage(Message.raw("Defeat cleanup failed: " + reason));
                return;
            }
            LOGGER.at(Level.INFO).log("defeat cleanup complete world=%s result=%s message=%s", runWorldUuid, result.success(), result.message());
            refreshedPlayer.sendMessage(Message.raw(result.message()));
        });
    }

    @Nullable
    private static Ref<EntityStore> chooseLivingCompanion(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> playerRef) {
        List<Ref<EntityStore>> tracked = TrackedSummonStore.getTrackedSnapshot(playerRef);
        return tracked.stream()
            .filter(ref -> ref != null && ref.isValid() && ref.getStore() == store)
            .filter(ref -> store.getComponent(ref, DeathComponent.getComponentType()) == null)
            .filter(ref -> readHealth(store, ref) > 0f)
            .max(Comparator.comparingDouble(ref -> readHealth(store, ref)))
            .orElse(null);
    }

    private static float readHealth(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        EntityStatMap stats = store.getComponent(ref, EntityStatMap.getComponentType());
        if (stats == null || stats.get(DefaultEntityStatTypes.getHealth()) == null) {
            return -1f;
        }
        return stats.get(DefaultEntityStatTypes.getHealth()).get();
    }

    private static void completeCompanionFallback(
        @Nonnull Ref<EntityStore> playerRef,
        @Nonnull Ref<EntityStore> survivor,
        @Nullable Integer survivorSlot,
        @Nullable Integer previousRoleIndex,
        @Nonnull PlayerRef player
    ) {
        if (!playerRef.isValid() || !survivor.isValid()) {
            return;
        }

        Store<EntityStore> store = playerRef.getStore();
        Ref<EntityStore> previousRoleCarrier = survivor;
        if (previousRoleIndex != null) {
            Ref<EntityStore> rebuilt = NpcCarrierRoleService.rebuildCarrierRole(store, survivor, previousRoleIndex);
            if (rebuilt != null && rebuilt.isValid()) {
                previousRoleCarrier = rebuilt;
            }
        }
        if (survivorSlot != null) {
            TrackedSummonStore.movePlayerToPartySlot(playerRef, survivorSlot, survivor, previousRoleCarrier);
        } else {
            TrackedSummonStore.setActiveNpc(playerRef, previousRoleCarrier);
        }
        player.sendMessage(Message.raw("You fall back into a surviving companion."));
    }
}
