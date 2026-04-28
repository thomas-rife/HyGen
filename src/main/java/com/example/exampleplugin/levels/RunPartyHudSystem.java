package com.example.exampleplugin.levels;

import com.example.exampleplugin.hud.RunPartyHud;
import com.example.exampleplugin.npc.AbilityRegistry;
import com.example.exampleplugin.npc.HeroAbility;
import com.example.exampleplugin.npc.TrackedSummonStore;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.protocol.packets.interface_.HudComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class RunPartyHudSystem extends TickingSystem<EntityStore> {
    private static final java.util.concurrent.ConcurrentHashMap.KeySetView<UUID, Boolean> FORCE_REFRESH_PLAYERS =
        java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.ConcurrentHashMap<UUID, RunPartyHud> huds = new java.util.concurrent.ConcurrentHashMap<>();
    private long lastTickMs;

    public static void requestImmediateRefresh(@Nonnull UUID playerUuid) {
        FORCE_REFRESH_PLAYERS.add(playerUuid);
    }

    @Override
    public void tick(float dt, int systemIndex, @Nonnull Store<EntityStore> store) {
        long now = System.currentTimeMillis();
        LevelSessionManager.ActiveRunSnapshot snapshot = LevelSessionManager.get().getSnapshot();
        boolean forceRefresh = snapshot != null && consumeForceRefresh(snapshot);
        if (!forceRefresh && now - this.lastTickMs < 16L) {
            return;
        }
        this.lastTickMs = now;

        if (snapshot == null || snapshot.runWorldUuid() == null) {
            clearAll(store);
            return;
        }

        World world = store.getExternalData().getWorld();
        UUID worldId = world.getWorldConfig().getUuid();
        if (!snapshot.runWorldUuid().equals(worldId)) {
            clearAll(store);
            return;
        }
        if (LevelRunDirectorSystem.isVictoryOverlayActive(worldId)) {
            clearAll(store);
            return;
        }

        PlayerRef owner = Universe.get().getPlayer(snapshot.ownerPlayerUuid());
        if (owner == null) {
            clearAll(store);
            return;
        }

        Ref<EntityStore> ownerRef = owner.getReference();
        if (ownerRef == null || !ownerRef.isValid() || ownerRef.getStore() != store) {
            clearAll(store);
            return;
        }

        if (TrackedSummonStore.getTrackedSnapshot(ownerRef).isEmpty()) {
            clearAll(store);
            return;
        }

        showHud(store, owner, ownerRef);
        for (UUID participantUuid : snapshot.participantPlayerUuids()) {
            if (participantUuid.equals(owner.getUuid())) {
                continue;
            }
            PlayerRef viewer = Universe.get().getPlayer(participantUuid);
            if (viewer != null) {
                showHud(store, viewer, ownerRef);
            }
        }
    }

    private void showHud(
        @Nonnull Store<EntityStore> store,
        @Nonnull PlayerRef viewer,
        @Nonnull Ref<EntityStore> ownerRef
    ) {
        Ref<EntityStore> viewerRef = viewer.getReference();
        if (viewerRef == null || !viewerRef.isValid() || viewerRef.getStore() != store) {
            return;
        }
        Player player = store.getComponent(viewerRef, Player.getComponentType());
        if (player == null) {
            return;
        }

        RunPartyHud hud = this.huds.computeIfAbsent(viewer.getUuid(), ignored -> new RunPartyHud(viewer));
        hud.setHeroSlots(buildHeroSlots(store, ownerRef, viewerRef));
        hud.setVisible(true);

        if (player.getHudManager().getCustomHud() != hud) {
            player.getHudManager().setCustomHud(viewer, hud);
        } else {
            hud.refresh();
        }
        player.getHudManager().setVisibleHudComponents(viewer, HudComponent.Reticle);
    }

    @Nonnull
    private static List<RunPartyHud.HeroSlot> buildHeroSlots(
        @Nonnull Store<EntityStore> store,
        @Nonnull Ref<EntityStore> ownerRef,
        @Nonnull Ref<EntityStore> viewerRef
    ) {
        List<RunPartyHud.HeroSlot> slots = new ArrayList<>();
        PlayerRef ownerPlayerRef = store.getComponent(ownerRef, PlayerRef.getComponentType());
        List<HeroSelectionStore.Hero> selectedHeroes = ownerPlayerRef == null
            ? List.of()
            : HeroSelectionStore.getSelectedHeroes(ownerPlayerRef.getUuid());
        int count = Math.min(selectedHeroes.size(), 8);
        for (int i = 0; i < count; i++) {
            int partySlot = i + 1;
            HeroSelectionStore.Hero hero = selectedHeroes.get(i);
            Ref<EntityStore> slotPlayer = TrackedSummonStore.getPlayerForPartySlot(ownerRef, partySlot);
            Ref<EntityStore> slotNpc = TrackedSummonStore.getNpcForUtilitySlot(ownerRef, partySlot);
            Ref<EntityStore> healthRef = slotPlayer != null ? slotPlayer : slotNpc;
            HealthValues health = readHealth(store, healthRef);
            boolean dead = healthRef != null && !isAlive(store, healthRef);
            boolean occupiedByOtherPlayer = slotPlayer != null && !slotPlayer.equals(viewerRef);
            boolean active = slotPlayer != null && slotPlayer.equals(viewerRef);
            HeroAbility ability = AbilityRegistry.getAbility(hero.roleId());

            boolean hasAbility = ability != null;
            boolean abilityActive = ability != null && ability.isAbilityActive(partySlot);
            float cooldownProgress = ability == null ? 0.0f : ability.getCooldownProgress(partySlot);
            String summary = getAbilitySummary(hero.roleId());

            slots.add(new RunPartyHud.HeroSlot(
                hero.displayName(),
                hero.itemId(),
                active,
                occupiedByOtherPlayer,
                health.current(),
                health.max(),
                hasAbility,
                abilityActive,
                cooldownProgress,
                summary,
                hero.id(),
                dead
            ));
        }
        return slots;
    }

    @Nullable
    private static String getAbilitySummary(@Nonnull String roleId) {
        return switch (roleId) {
            case "HyGen_Companion_Archer" -> "Critical Shot: Loads a critical arrow; next hit does 6x damage to the target.";
            case "HyGen_Companion_Barbarian" -> "Rage: Increases all damage dealt by 1.5x for 5s.";
            case "HyGen_Companion_Mage" -> "Arcane Burst: Deals 80 damage to all enemies within 8m.";
            case "HyGen_Companion_Vanguard" -> "Guard: Reduces all incoming damage by 80% for 10s.";
            case "HyGen_Companion_Oracle" -> "Divine Shield: Grants the entire party 5s of invulnerability.";
            case "HyGen_Companion_Wizard" -> "Time Warp: Slows all active enemies for 3s.";
            case "HyGen_Companion_Monk" -> "Burst Heal: Instantly restores the most injured ally to full health.";
            case "HyGen_Companion_Warden" -> "Healing Aura: Restores 40% health to all party members.";
            default -> null;
        };
    }

    @Nonnull
    private static HealthValues readHealth(@Nonnull Store<EntityStore> store, @Nullable Ref<EntityStore> ref) {
        if (!isAlive(store, ref)) {
            return new HealthValues(0f, 1f);
        }
        EntityStatMap stats = store.getComponent(ref, EntityStatMap.getComponentType());
        if (stats == null) {
            return new HealthValues(0f, 1f);
        }
        EntityStatValue health = stats.get(DefaultEntityStatTypes.getHealth());
        if (health == null) {
            return new HealthValues(0f, 1f);
        }
        return new HealthValues(health.get(), Math.max(health.getMax(), 1f));
    }

    private void clearAll(@Nonnull Store<EntityStore> store) {
        World world = store.getExternalData().getWorld();
        for (PlayerRef playerRef : world.getPlayerRefs()) {
            hideHud(store, playerRef);
        }
    }

    private void hideHud(@Nonnull Store<EntityStore> store, @Nonnull PlayerRef playerRef) {
        Ref<EntityStore> ref = playerRef.getReference();
        if (ref == null || !ref.isValid() || ref.getStore() != store) {
            return;
        }
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            return;
        }
        if (player.getHudManager().getCustomHud() instanceof RunPartyHud) {
            player.getHudManager().resetHud(playerRef);
        }
    }

    private static boolean isAlive(@Nonnull Store<EntityStore> store, @Nullable Ref<EntityStore> ref) {
        return ref != null && ref.isValid() && ref.getStore() == store && store.getComponent(ref, DeathComponent.getComponentType()) == null;
    }

    private record HealthValues(float current, float max) {
    }

    private static boolean consumeForceRefresh(@Nonnull LevelSessionManager.ActiveRunSnapshot snapshot) {
        boolean forceRefresh = FORCE_REFRESH_PLAYERS.remove(snapshot.ownerPlayerUuid());
        for (UUID playerUuid : snapshot.participantPlayerUuids()) {
            forceRefresh |= FORCE_REFRESH_PLAYERS.remove(playerUuid);
        }
        return forceRefresh;
    }
}
