package com.example.exampleplugin.npc;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.PlayerSkin;
import com.hypixel.hytale.server.core.entity.Frozen;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerSkinComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;

public final class TrackedSummonStore {
    private static final HytaleLogger LOGGER = HytaleLogger.get("Battleheart|PartyTrack");
    private static final int MAX_WHEEL_SLOTS = 4;
    private static final ConcurrentHashMap<UUID, PlayerTrackedData> TRACKED_BY_PLAYER = new ConcurrentHashMap<>();

    private TrackedSummonStore() {
    }

    public static void trackInNextSlot(Ref<EntityStore> playerRef, Ref<EntityStore> npcRef, int roleIndex) {
        if (playerRef == null || npcRef == null) {
            LOGGER.at(Level.WARNING).log("trackInNextSlot ignored player=%s npc=%s", playerRef, npcRef);
            return;
        }

        UUID playerUuid = getPlayerUuid(playerRef);
        if (playerUuid == null) return;
        PlayerTrackedData data = TRACKED_BY_PLAYER.computeIfAbsent(playerUuid, ignored -> new PlayerTrackedData());
        int assigned = data.slotIndexByRef.getOrDefault(npcRef, -1);
        if (assigned < 0) {
            assigned = nextOpenSlot(data);
        }
        trackInSlot(playerRef, npcRef, roleIndex, assigned);
    }

    @Nullable
    public static Ref<EntityStore> trackInSlot(Ref<EntityStore> playerRef, Ref<EntityStore> npcRef, int roleIndex, int slot) {
        if (playerRef == null || npcRef == null) {
            LOGGER.at(Level.WARNING).log("trackInSlot ignored player=%s npc=%s roleIndex=%s slot=%s", playerRef, npcRef, roleIndex, slot);
            return null;
        }

        UUID playerUuid = getPlayerUuid(playerRef);
        if (playerUuid == null) return null;
        PlayerTrackedData data = TRACKED_BY_PLAYER.computeIfAbsent(playerUuid, ignored -> new PlayerTrackedData());
        data.activeNpcRef = npcRef;
        data.activeRoleIndex = roleIndex;
        data.trackedNpcs.addIfAbsent(npcRef);

        if (slot >= 0 && slot < MAX_WHEEL_SLOTS) {
            Ref<EntityStore> replaced = data.npcBySlot[slot];
            if (replaced != null) {
                data.slotIndexByRef.remove(replaced);
                if (replaced == data.defaultCarrierNpcRef) {
                    data.defaultCarrierNpcRef = null;
                }
            }
            data.npcBySlot[slot] = npcRef;
            data.roleIndexBySlot[slot] = roleIndex;
            data.slotIndexByRef.put(npcRef, slot);
            LOGGER.at(Level.INFO).log(
                "trackInSlot player=%s npc=%s roleIndex=%s slot=%s replaced=%s state=%s",
                playerRef,
                npcRef,
                roleIndex,
                slot,
                replaced,
                describePartyState(playerRef, npcRef.getStore())
            );
            return replaced;
        }

        return null;
    }

    public static void setPartyRoleIndex(Ref<EntityStore> playerRef, int slot, int roleIndex) {
        PlayerTrackedData data = findPartyData(playerRef);
        if (data == null || slot < 0 || slot >= MAX_WHEEL_SLOTS) {
            LOGGER.at(Level.WARNING).log("setPartyRoleIndex ignored player=%s slot=%s roleIndex=%s data=%s", playerRef, slot, roleIndex, data != null);
            return;
        }
        data.roleIndexBySlot[slot] = roleIndex;
        LOGGER.at(Level.INFO).log("setPartyRoleIndex player=%s slot=%s roleIndex=%s", playerRef, slot, roleIndex);
    }

    public static void setPartyAppearance(
        Ref<EntityStore> playerRef,
        int slot,
        @Nullable PlayerSkin skin
    ) {
        PlayerTrackedData data = findPartyData(playerRef);
        if (data == null || slot < 0 || slot >= MAX_WHEEL_SLOTS) {
            LOGGER.at(Level.WARNING).log("setPartyAppearance ignored player=%s slot=%s data=%s", playerRef, slot, data != null);
            return;
        }
        data.appearanceBySlot[slot] = new RoleAppearance(skin);
        LOGGER.at(Level.INFO).log(
            "setPartyAppearance player=%s slot=%s skin=%s",
            playerRef,
            slot,
            skin != null
        );
    }

    @Nullable
    public static RoleAppearance getPartyAppearance(Ref<EntityStore> playerRef, int slot) {
        PlayerTrackedData data = findPartyData(playerRef);
        if (data == null || slot < 0 || slot >= MAX_WHEEL_SLOTS) {
            return null;
        }
        return data.appearanceBySlot[slot];
    }

    public static void applyAppearance(
        @Nonnull ComponentAccessor<EntityStore> accessor,
        @Nonnull Ref<EntityStore> entityRef,
        @Nullable RoleAppearance appearance
    ) {
        if (appearance == null) {
            return;
        }
        if (appearance.skin() == null) {
            if (accessor.getComponent(entityRef, PlayerSkinComponent.getComponentType()) != null) {
                accessor.removeComponent(entityRef, PlayerSkinComponent.getComponentType());
            }
        } else {
            accessor.putComponent(entityRef, PlayerSkinComponent.getComponentType(), new PlayerSkinComponent(appearance.skin()));
        }
    }

    public static void configurePartyPlayers(Ref<EntityStore> ownerRef, List<Ref<EntityStore>> playerRefs) {
        if (ownerRef == null) {
            LOGGER.at(Level.WARNING).log("configurePartyPlayers ignored owner=null");
            return;
        }

        UUID ownerUuid = getPlayerUuid(ownerRef);
        if (ownerUuid == null) return;
        LOGGER.at(Level.INFO).log("configurePartyPlayers owner=%s players=%s", ownerRef, playerRefs);
        for (Ref<EntityStore> playerRef : playerRefs) {
            if (playerRef != null && !playerRef.equals(ownerRef)) {
                UUID pUuid = getPlayerUuid(playerRef);
                if (pUuid != null) TRACKED_BY_PLAYER.remove(pUuid);
            }
        }

        PlayerTrackedData data = TRACKED_BY_PLAYER.computeIfAbsent(ownerUuid, ignored -> new PlayerTrackedData());
        data.activeSlotByPlayer.clear();
        for (int i = 0; i < data.playerBySlot.length; i++) {
            data.playerBySlot[i] = null;
        }

        int slot = 1;
        for (Ref<EntityStore> playerRef : playerRefs) {
            if (playerRef == null || slot >= MAX_WHEEL_SLOTS) {
                continue;
            }
            data.playerBySlot[slot] = playerRef;
            data.activeSlotByPlayer.put(playerRef, slot);
            slot++;
        }
        LOGGER.at(Level.INFO).log("configurePartyPlayers state=%s", describePartyState(ownerRef, ownerRef.getStore()));
    }

    @Nullable
    public static Ref<EntityStore> getActiveNpcRef(Ref<EntityStore> playerRef) {
        PlayerTrackedData data = findPartyData(playerRef);
        return data == null ? null : data.activeNpcRef;
    }

    @Nullable
    public static Integer getActiveRoleIndex(Ref<EntityStore> playerRef) {
        PlayerTrackedData data = findPartyData(playerRef);
        return data == null ? null : data.activeRoleIndex;
    }

    @Nullable
    public static Ref<EntityStore> getNpcForUtilitySlot(Ref<EntityStore> playerRef, int slot) {
        PlayerTrackedData data = findPartyData(playerRef);
        if (data == null || slot < 0 || slot >= MAX_WHEEL_SLOTS) {
            return null;
        }
        return data.npcBySlot[slot];
    }

    @Nullable
    public static Ref<EntityStore> getPlayerForPartySlot(Ref<EntityStore> playerRef, int slot) {
        PlayerTrackedData data = findPartyData(playerRef);
        if (data == null || slot < 0 || slot >= MAX_WHEEL_SLOTS) {
            return null;
        }
        return data.playerBySlot[slot];
    }

    public static boolean setTargetForActivePartySlot(Ref<EntityStore> playerRef, Ref<EntityStore> targetRef, Store<EntityStore> store) {
        PlayerTrackedData data = findPartyData(playerRef);
        if (data == null || targetRef == null || !CombatTargetingUtil.isAlive(store, targetRef)) {
            LOGGER.at(Level.WARNING).log(
                "setTargetForActivePartySlot ignored player=%s target=%s data=%s alive=%s",
                playerRef,
                targetRef,
                data != null,
                targetRef != null && CombatTargetingUtil.isAlive(store, targetRef)
            );
            return false;
        }

        Integer activeSlot = data.activeSlotByPlayer.get(playerRef);
        if (activeSlot == null || activeSlot <= 0 || activeSlot >= MAX_WHEEL_SLOTS) {
            LOGGER.at(Level.WARNING).log("setTargetForActivePartySlot ignored bad activeSlot=%s player=%s", activeSlot, playerRef);
            return false;
        }

        data.targetBySlot[activeSlot] = targetRef;
        boolean applied = applyStoredTargetToNpc(data, activeSlot, store);
        LOGGER.at(Level.INFO).log(
            "setTargetForActivePartySlot player=%s activeSlot=%s target=%s appliedToNpc=%s",
            playerRef,
            activeSlot,
            targetRef,
            applied
        );
        return true;
    }

    @Nullable
    public static Ref<EntityStore> getTargetForActivePartySlot(Ref<EntityStore> playerRef, Store<EntityStore> store) {
        PlayerTrackedData data = findPartyData(playerRef);
        if (data == null) {
            return null;
        }

        Integer activeSlot = data.activeSlotByPlayer.get(playerRef);
        if (activeSlot == null || activeSlot <= 0 || activeSlot >= MAX_WHEEL_SLOTS) {
            return null;
        }

        Ref<EntityStore> targetRef = data.targetBySlot[activeSlot];
        if (!CombatTargetingUtil.isAlive(store, targetRef)) {
            data.targetBySlot[activeSlot] = null;
            return null;
        }
        return targetRef;
    }

    @Nullable
    public static Ref<EntityStore> getTargetForPartySlot(Ref<EntityStore> playerRef, int slot, Store<EntityStore> store) {
        PlayerTrackedData data = findPartyData(playerRef);
        if (data == null || slot <= 0 || slot >= MAX_WHEEL_SLOTS) {
            return null;
        }

        Ref<EntityStore> targetRef = data.targetBySlot[slot];
        if (!CombatTargetingUtil.isAlive(store, targetRef)) {
            data.targetBySlot[slot] = null;
            return null;
        }
        return targetRef;
    }

    public static void clearTargetForActivePartySlot(Ref<EntityStore> playerRef, Store<EntityStore> store) {
        PlayerTrackedData data = findPartyData(playerRef);
        if (data == null) {
            return;
        }

        Integer activeSlot = data.activeSlotByPlayer.get(playerRef);
        if (activeSlot == null || activeSlot <= 0 || activeSlot >= MAX_WHEEL_SLOTS) {
            return;
        }

        data.targetBySlot[activeSlot] = null;
        applyStoredTargetToNpc(data, activeSlot, store);
    }

    public static void clearTargetForNpc(Ref<EntityStore> ownerRef, Ref<EntityStore> npcRef, Ref<EntityStore> expectedTargetRef) {
        PlayerTrackedData data = findPartyData(ownerRef);
        if (data == null || npcRef == null) {
            return;
        }

        Integer slot = data.slotIndexByRef.get(npcRef);
        if (slot == null || slot <= 0 || slot >= MAX_WHEEL_SLOTS) {
            return;
        }
        Ref<EntityStore> stored = data.targetBySlot[slot];
        if (expectedTargetRef == null || expectedTargetRef.equals(stored)) {
            data.targetBySlot[slot] = null;
        }
    }

    public static void clearStoredTargetIfMatches(Ref<EntityStore> ownerRef, Ref<EntityStore> targetRef) {
        PlayerTrackedData data = findPartyData(ownerRef);
        if (data == null || targetRef == null) {
            return;
        }

        for (int slot = 1; slot < MAX_WHEEL_SLOTS; slot++) {
            if (targetRef.equals(data.targetBySlot[slot])) {
                data.targetBySlot[slot] = null;
            }
        }
    }

    public static boolean syncStoredTargetForNpc(
        Ref<EntityStore> ownerRef,
        Ref<EntityStore> npcRef,
        Store<EntityStore> store
    ) {
        PlayerTrackedData data = findPartyData(ownerRef);
        if (data == null || npcRef == null) {
            return false;
        }

        Integer slot = data.slotIndexByRef.get(npcRef);
        if (slot == null || slot <= 0 || slot >= MAX_WHEEL_SLOTS) {
            return false;
        }
        return applyStoredTargetToNpc(data, slot, store);
    }

    @Nullable
    public static Integer getActivePartySlot(Ref<EntityStore> playerRef) {
        PlayerTrackedData data = findPartyData(playerRef);
        return data == null ? null : data.activeSlotByPlayer.get(playerRef);
    }

    @Nullable
    public static Integer getActivePartySlot(@Nonnull PlayerRef playerRef) {
        PlayerTrackedData data = findPartyData(playerRef);
        if (data == null) {
            return null;
        }
        Ref<EntityStore> entityRef = playerRef.getReference();
        return entityRef == null ? null : data.activeSlotByPlayer.get(entityRef);
    }

    public static boolean isPartySlotOccupiedByOther(Ref<EntityStore> playerRef, int slot) {
        PlayerTrackedData data = findPartyData(playerRef);
        if (data == null || slot <= 0 || slot >= MAX_WHEEL_SLOTS) {
            return false;
        }

        Ref<EntityStore> occupyingPlayer = data.playerBySlot[slot];
        return occupyingPlayer != null && !occupyingPlayer.equals(playerRef);
    }

    @Nullable
    public static Integer getPartyRoleIndex(Ref<EntityStore> playerRef, int slot) {
        PlayerTrackedData data = findPartyData(playerRef);
        if (data == null || slot < 0 || slot >= MAX_WHEEL_SLOTS) {
            return null;
        }
        return data.roleIndexBySlot[slot];
    }

    @Nullable
    public static Integer getPartyRoleIndex(@Nonnull PlayerRef playerRef, int slot) {
        PlayerTrackedData data = findPartyData(playerRef);
        if (data == null || slot < 0 || slot >= MAX_WHEEL_SLOTS) {
            return null;
        }
        return data.roleIndexBySlot[slot];
    }

    @Nullable
    public static Integer getPartySlotForNpc(Ref<EntityStore> playerRef, Ref<EntityStore> npcRef) {
        PlayerTrackedData data = findPartyData(playerRef);
        if (data == null || npcRef == null) {
            return null;
        }
        return data.slotIndexByRef.get(npcRef);
    }

    public static boolean movePlayerToPartySlot(Ref<EntityStore> playerRef, int targetSlot, Ref<EntityStore> previousRoleCarrierRef) {
        return movePlayerToPartySlot(playerRef, targetSlot, previousRoleCarrierRef, previousRoleCarrierRef);
    }

    public static boolean movePlayerToPartySlot(
        Ref<EntityStore> playerRef,
        int targetSlot,
        Ref<EntityStore> oldTargetCarrierRef,
        Ref<EntityStore> previousRoleCarrierRef
    ) {
        PlayerTrackedData data = findPartyData(playerRef);
        if (data == null || targetSlot <= 0 || targetSlot >= MAX_WHEEL_SLOTS || previousRoleCarrierRef == null) {
            LOGGER.at(Level.WARNING).log(
                "movePlayerToPartySlot ignored player=%s targetSlot=%s previousRoleCarrier=%s data=%s",
                playerRef,
                targetSlot,
                previousRoleCarrierRef,
                data != null
            );
            return false;
        }

        Integer previousSlot = data.activeSlotByPlayer.get(playerRef);
        if (previousSlot == null || previousSlot == targetSlot) {
            LOGGER.at(Level.WARNING).log(
                "movePlayerToPartySlot ignored previousSlot=%s targetSlot=%s player=%s",
                previousSlot,
                targetSlot,
                playerRef
            );
            return false;
        }

        Ref<EntityStore> occupyingPlayer = data.playerBySlot[targetSlot];
        if (occupyingPlayer != null && !occupyingPlayer.equals(playerRef)) {
            LOGGER.at(Level.WARNING).log(
                "movePlayerToPartySlot blocked player=%s targetSlot=%s occupyingPlayer=%s",
                playerRef,
                targetSlot,
                occupyingPlayer
            );
            return false;
        }

        LOGGER.at(Level.INFO).log(
            "movePlayerToPartySlot before player=%s previousSlot=%s targetSlot=%s oldTargetCarrier=%s previousRoleCarrier=%s state=%s",
            playerRef,
            previousSlot,
            targetSlot,
            oldTargetCarrierRef,
            previousRoleCarrierRef,
            describePartyState(playerRef, playerRef.getStore())
        );
        data.playerBySlot[previousSlot] = null;
        data.playerBySlot[targetSlot] = playerRef;
        data.activeSlotByPlayer.put(playerRef, targetSlot);

        data.npcBySlot[previousSlot] = previousRoleCarrierRef;
        data.slotIndexByRef.put(previousRoleCarrierRef, previousSlot);
        data.npcBySlot[targetSlot] = null;
        data.slotIndexByRef.remove(oldTargetCarrierRef, targetSlot);
        data.slotIndexByRef.remove(previousRoleCarrierRef, targetSlot);
        if (oldTargetCarrierRef != null && !oldTargetCarrierRef.equals(previousRoleCarrierRef)) {
            data.trackedNpcs.remove(oldTargetCarrierRef);
            data.trackedNpcs.addIfAbsent(previousRoleCarrierRef);
        }
        data.activeNpcRef = previousRoleCarrierRef;
        boolean applied = applyStoredTargetToNpc(data, previousSlot, playerRef.getStore());
        LOGGER.at(Level.INFO).log(
            "movePlayerToPartySlot after appliedPreviousTarget=%s state=%s",
            applied,
            describePartyState(playerRef, playerRef.getStore())
        );
        return true;
    }

    @Nullable
    public static Ref<EntityStore> getDefaultCarrierNpcRef(Ref<EntityStore> playerRef) {
        PlayerTrackedData data = findPartyData(playerRef);
        return data == null ? null : data.defaultCarrierNpcRef;
    }

    public static void setDefaultCarrierNpcRef(Ref<EntityStore> playerRef, @Nullable Ref<EntityStore> npcRef) {
        PlayerTrackedData data = findPartyData(playerRef);
        if (data == null) {
            return;
        }
        data.defaultCarrierNpcRef = npcRef;
    }

    public static void setActiveNpc(Ref<EntityStore> playerRef, Ref<EntityStore> npcRef) {
        PlayerTrackedData data = findPartyData(playerRef);
        if (data == null) {
            return;
        }
        data.activeNpcRef = npcRef;
    }

    public static List<Ref<EntityStore>> getTrackedSnapshot(Ref<EntityStore> playerRef) {
        PlayerTrackedData data = findPartyData(playerRef);
        return data == null ? List.of() : new ArrayList<>(data.trackedNpcs);
    }

    @Nonnull
    public static String describePartyState(@Nullable Ref<EntityStore> playerRef, @Nullable Store<EntityStore> store) {
        PlayerTrackedData data = findPartyData(playerRef);
        if (data == null) {
            return "no party tracking";
        }

        StringBuilder out = new StringBuilder();
        out.append("activeSlot=").append(playerRef == null ? null : data.activeSlotByPlayer.get(playerRef));
        out.append(" activeNpc=").append(describeRef(data.activeNpcRef, store));
        out.append(" tracked=").append(data.trackedNpcs.size());
        for (int slot = 1; slot < MAX_WHEEL_SLOTS; slot++) {
            Integer roleIndex = data.roleIndexBySlot[slot];
            out.append(" slot").append(slot)
                .append("{role=").append(roleIndex == null ? null : NPCPlugin.get().getName(roleIndex))
                .append(",player=").append(describeRef(data.playerBySlot[slot], store))
                .append(",npc=").append(describeRef(data.npcBySlot[slot], store))
                .append(",target=").append(describeRef(data.targetBySlot[slot], store))
                .append("}");
        }
        return out.toString();
    }

    @Nonnull
    private static String describeRef(@Nullable Ref<EntityStore> ref, @Nullable Store<EntityStore> store) {
        if (ref == null) {
            return "null";
        }

        StringBuilder out = new StringBuilder(ref.toString());
        out.append(ref.isValid() ? ":valid" : ":invalid");
        if (!ref.isValid() || store == null || ref.getStore() != store) {
            return out.toString();
        }

        NPCEntity npc = store.getComponent(ref, NPCEntity.getComponentType());
        Player player = store.getComponent(ref, Player.getComponentType());
        ModelComponent model = store.getComponent(ref, ModelComponent.getComponentType());
        PlayerSkinComponent skin = store.getComponent(ref, PlayerSkinComponent.getComponentType());
        Frozen frozen = store.getComponent(ref, Frozen.getComponentType());
        out.append("[");
        if (player != null) {
            out.append("player");
        }
        if (npc != null) {
            if (player != null) {
                out.append(",");
            }
            out.append("npc=").append(npc.getRoleName());
        }
        out.append(",model=").append(model == null || model.getModel() == null ? "none" : model.getModel().getModelAssetId());
        out.append(",skin=").append(skin != null);
        out.append(",frozen=").append(frozen != null);
        out.append("]");
        return out.toString();
    }

    public static boolean isTrackedNpc(@Nullable Ref<EntityStore> npcRef) {
        if (npcRef == null || !npcRef.isValid()) {
            return false;
        }

        for (PlayerTrackedData data : TRACKED_BY_PLAYER.values()) {
            if (data.trackedNpcs.contains(npcRef)) {
                return true;
            }
        }
        return false;
    }

    public static void clearTracking(Ref<EntityStore> playerRef) {
        UUID playerUuid = getPlayerUuid(playerRef);
        if (playerUuid == null) return;
        PlayerTrackedData data = TRACKED_BY_PLAYER.remove(playerUuid);
        if (data == null) {
            LOGGER.at(Level.INFO).log("clearTracking no data player=%s", playerRef);
            return;
        }

        LOGGER.at(Level.INFO).log("clearTracking player=%s trackedCount=%s", playerRef, data.trackedNpcs.size());
        data.activeNpcRef = null;
        data.activeRoleIndex = null;
        data.defaultCarrierNpcRef = null;
        data.trackedNpcs.clear();
        data.slotIndexByRef.clear();
        data.activeSlotByPlayer.clear();
        for (int i = 0; i < data.npcBySlot.length; i++) {
            data.npcBySlot[i] = null;
            data.playerBySlot[i] = null;
            data.roleIndexBySlot[i] = null;
            data.targetBySlot[i] = null;
            data.appearanceBySlot[i] = null;
        }
    }

    private static boolean applyStoredTargetToNpc(PlayerTrackedData data, int slot, Store<EntityStore> store) {
        if (slot <= 0 || slot >= MAX_WHEEL_SLOTS) {
            return false;
        }
        Ref<EntityStore> npcRef = data.npcBySlot[slot];
        Ref<EntityStore> targetRef = data.targetBySlot[slot];
        if (npcRef == null || !npcRef.isValid() || npcRef.getStore() != store) {
            return false;
        }
        if (!CombatTargetingUtil.isAlive(store, targetRef)) {
            data.targetBySlot[slot] = null;
            CombatTargetingUtil.clearMarkedTarget(store, npcRef);
            return false;
        }
        
        if (CombatTargetingUtil.isLiveEnemyTarget(store, targetRef)) {
            CombatTargetingUtil.setTargetHostile(store, npcRef, targetRef);
            if (store.getComponent(npcRef, Frozen.getComponentType()) != null) {
                store.removeComponent(npcRef, Frozen.getComponentType());
            }
        } else {
            // It's an ally (or neutral target), clear hostile AI target so they don't attack it
            CombatTargetingUtil.clearMarkedTarget(store, npcRef);
        }
        return true;
    }

    @Nullable
    private static PlayerTrackedData findPartyData(Ref<EntityStore> playerRef) {
        UUID playerUuid = getPlayerUuid(playerRef);
        if (playerUuid != null) {
            PlayerTrackedData direct = TRACKED_BY_PLAYER.get(playerUuid);
            if (direct != null) {
                return direct;
            }
        }

        for (PlayerTrackedData data : TRACKED_BY_PLAYER.values()) {
            if (data.activeSlotByPlayer.containsKey(playerRef)) {
                return data;
            }
        }
        return null;
    }

    @Nullable
    private static PlayerTrackedData findPartyData(@Nonnull PlayerRef playerRef) {
        PlayerTrackedData direct = TRACKED_BY_PLAYER.get(playerRef.getUuid());
        if (direct != null) {
            return direct;
        }

        Ref<EntityStore> entityRef = playerRef.getReference();
        if (entityRef == null) {
            return null;
        }

        for (PlayerTrackedData data : TRACKED_BY_PLAYER.values()) {
            if (data.activeSlotByPlayer.containsKey(entityRef)) {
                return data;
            }
        }
        return null;
    }

    @Nullable
    private static UUID getPlayerUuid(Ref<EntityStore> ref) {
        if (ref == null || !ref.isValid()) return null;
        PlayerRef comp = ref.getStore().getComponent(ref, PlayerRef.getComponentType());
        return comp == null ? null : comp.getUuid();
    }

    private static int nextOpenSlot(PlayerTrackedData data) {
        for (int i = 1; i < MAX_WHEEL_SLOTS; i++) {
            if (data.npcBySlot[i] == null) {
                return i;
            }
        }
        int index = (data.trackedNpcs.size() % (MAX_WHEEL_SLOTS - 1)) + 1;
        return index;
    }

    private static final class PlayerTrackedData {
        private final CopyOnWriteArrayList<Ref<EntityStore>> trackedNpcs = new CopyOnWriteArrayList<>();
        private final ConcurrentHashMap<Ref<EntityStore>, Integer> slotIndexByRef = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<Ref<EntityStore>, Integer> activeSlotByPlayer = new ConcurrentHashMap<>();
        private final Ref<EntityStore>[] npcBySlot = new Ref[MAX_WHEEL_SLOTS];
        private final Ref<EntityStore>[] playerBySlot = new Ref[MAX_WHEEL_SLOTS];
        private final Ref<EntityStore>[] targetBySlot = new Ref[MAX_WHEEL_SLOTS];
        private final Integer[] roleIndexBySlot = new Integer[MAX_WHEEL_SLOTS];
        private final RoleAppearance[] appearanceBySlot = new RoleAppearance[MAX_WHEEL_SLOTS];
        private volatile Ref<EntityStore> activeNpcRef;
        private volatile Ref<EntityStore> defaultCarrierNpcRef;
        private volatile Integer activeRoleIndex;
    }

    public record RoleAppearance(@Nullable PlayerSkin skin) {
    }
}
