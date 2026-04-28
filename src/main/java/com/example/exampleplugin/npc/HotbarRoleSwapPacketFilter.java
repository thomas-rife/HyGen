package com.example.exampleplugin.npc;

import com.example.exampleplugin.levels.LevelRunCombatStore;
import com.example.exampleplugin.levels.LevelSessionManager;
import com.example.exampleplugin.levels.RunPartyHudSystem;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.MouseButtonState;
import com.hypixel.hytale.protocol.MouseButtonType;
import com.hypixel.hytale.protocol.Packet;
import com.hypixel.hytale.protocol.packets.interaction.CancelInteractionChain;
import com.hypixel.hytale.protocol.packets.interaction.SyncInteractionChain;
import com.hypixel.hytale.protocol.packets.interaction.SyncInteractionChains;
import com.hypixel.hytale.protocol.packets.inventory.SetActiveSlot;
import com.hypixel.hytale.protocol.packets.player.MouseInteraction;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.io.adapter.PlayerPacketFilter;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

public class HotbarRoleSwapPacketFilter implements PlayerPacketFilter {
    private static final DisabledLogger LOGGER = new DisabledLogger();
    private static final int NEUTRAL_HOTBAR_SLOT = 3;
    private static final InteractionType HERO_ABILITY_INTERACTION = InteractionType.Ability1;
    
    // Set this to true to temporarily disable targeting enemies/allies with cursor clicks/look-at
    private static final boolean DISABLE_CURSOR_TARGETING = true;

    @Override
    public boolean test(PlayerRef playerRef, Packet packet) {
        if (packet instanceof MouseInteraction mouseInteraction) {
            return handleMouseInteraction(playerRef, mouseInteraction);
        }
        if (!(packet instanceof SyncInteractionChains syncPacket)) {
            return false;
        }
        if (!shouldHandlePlayer(playerRef)) {
            LOGGER.at(Level.INFO).log(
                "SyncInteractionChains ignored: shouldHandlePlayer=false player=%s updates=%s",
                playerRef.getUuid(),
                syncPacket.updates == null ? 0 : syncPacket.updates.length
            );
            return false;
        }

        SyncInteractionChain roleSwapChain = null;
        List<SyncInteractionChain> keep = new ArrayList<>();
        boolean consumedTargetAssignment = false;
        boolean consumedAbilityInput = false;
        LOGGER.at(Level.INFO).log(
            "SyncInteractionChains player=%s updates=%s",
            playerRef.getUuid(),
            syncPacket.updates == null ? 0 : syncPacket.updates.length
        );
        for (SyncInteractionChain chain : syncPacket.updates) {
            LOGGER.at(Level.INFO).log("chain %s", describeChain(chain));
            if (isRoleSwapChain(chain) && roleSwapChain == null) {
                roleSwapChain = chain;
            } else {
                boolean handledAsAbility = false;
                if (isAbilityChain(chain)) {
                    if (chain.initial) {
                        if (shouldUseVanguardGuardInput(playerRef)) {
                            handleVanguardGuard(playerRef, chain);
                        } else {
                            HeroAbility ability = resolveActiveAbility(playerRef);
                            if (ability != null) {
                                handleAbilityInput(playerRef, chain, ability);
                            }
                        }
                    }
                    cancelInteraction(playerRef, chain);
                    consumedAbilityInput = true;
                    handledAsAbility = true;
                }

                if (!handledAsAbility) {
                    if (isTargetAssignmentInput(chain)) {
                        handleTargetAssignment(playerRef, chain);
                        cancelInteraction(playerRef, chain);
                        consumedTargetAssignment = true;
                    } else {
                        keep.add(chain);
                    }
                }
            }
        }

        if (roleSwapChain == null) {
            if (consumedAbilityInput) {
                LOGGER.at(Level.INFO).log(
                    "SyncInteractionChains consumed ability input keep=%s",
                    keep.size()
                );
                if (!keep.isEmpty()) {
                    syncPacket.updates = keep.toArray(new SyncInteractionChain[0]);
                    return false;
                }
                return true;
            }
            if (consumedTargetAssignment) {
                LOGGER.at(Level.INFO).log(
                    "SyncInteractionChains consumed target assignment keep=%s",
                    keep.size()
                );
                if (!keep.isEmpty()) {
                    syncPacket.updates = keep.toArray(new SyncInteractionChain[0]);
                    return false;
                }
                return true;
            }
            LOGGER.at(Level.INFO).log("SyncInteractionChains no role swap chain");
            return false;
        }

        LOGGER.at(Level.INFO).log("role swap chain selected %s", describeChain(roleSwapChain));
        handleRoleSwap(playerRef, roleSwapChain);
        if (!keep.isEmpty()) {
            syncPacket.updates = keep.toArray(new SyncInteractionChain[0]);
            return false;
        }
        return true;
    }

    private static boolean isAbilityChain(SyncInteractionChain chain) {
        return chain != null
            && (chain.interactionType == InteractionType.Ability1
                || chain.interactionType == InteractionType.Use
                || chain.interactionType == InteractionType.Secondary);
    }

    private static boolean shouldUseVanguardGuardInput(@Nonnull PlayerRef playerRef) {
        Integer activeSlot = TrackedSummonStore.getActivePartySlot(playerRef);
        if (activeSlot == null) {
            return false;
        }
        Integer roleIndex = TrackedSummonStore.getPartyRoleIndex(playerRef, activeSlot);
        if (roleIndex == null) {
            return false;
        }
        String roleId = com.hypixel.hytale.server.npc.NPCPlugin.get().getName(roleIndex);
        return VanguardGuardAbility.VANGUARD_ROLE_ID.equals(roleId);
    }

    private static void handleVanguardGuard(@Nonnull PlayerRef playerRef, @Nonnull SyncInteractionChain chain) {
        Ref<EntityStore> playerEntityRef = playerRef.getReference();
        if (playerEntityRef == null || !playerEntityRef.isValid()) {
            return;
        }

        Store<EntityStore> store = playerEntityRef.getStore();
        World world = store.getExternalData().getWorld();
        HeroAbility vanguardAbility = AbilityRegistry.getAbility(VanguardGuardAbility.VANGUARD_ROLE_ID);
        world.execute(() -> {
            boolean activated = vanguardAbility != null
                ? vanguardAbility.tryActivate(store, playerEntityRef)
                : new VanguardGuardAbility().tryActivate(store, playerEntityRef);
            LOGGER.at(Level.INFO).log(
                "vanguard guard input player=%s chain=%s activated=%s",
                playerEntityRef,
                describeChain(chain),
                activated
            );
            if (activated) {
                RunPartyHudSystem.requestImmediateRefresh(playerRef.getUuid());
            }
        });
    }

    @Nullable
    private static HeroAbility resolveActiveAbility(@Nonnull PlayerRef playerRef) {
        Integer activeSlot = TrackedSummonStore.getActivePartySlot(playerRef);
        if (activeSlot == null) {
            return null;
        }
        Integer roleIndex = TrackedSummonStore.getPartyRoleIndex(playerRef, activeSlot);
        if (roleIndex == null) {
            return null;
        }
        String roleId = com.hypixel.hytale.server.npc.NPCPlugin.get().getName(roleIndex);
        return AbilityRegistry.getAbility(roleId);
    }

    private static void handleAbilityInput(@Nonnull PlayerRef playerRef, @Nonnull SyncInteractionChain chain, @Nonnull HeroAbility ability) {
        Ref<EntityStore> playerEntityRef = playerRef.getReference();
        if (playerEntityRef == null || !playerEntityRef.isValid()) {
            return;
        }

        Store<EntityStore> store = playerEntityRef.getStore();
        World world = store.getExternalData().getWorld();
        world.execute(() -> {
            boolean activated = ability.tryActivate(store, playerEntityRef);
            LOGGER.at(Level.INFO).log(
                "ability input player=%s chain=%s activated=%s roleId=%s",
                playerEntityRef,
                describeChain(chain),
                activated,
                ability.getRoleId()
            );
            if (activated) {
                RunPartyHudSystem.requestImmediateRefresh(playerRef.getUuid());
            }
        });
    }

    private static boolean handleMouseInteraction(@Nonnull PlayerRef playerRef, @Nonnull MouseInteraction packet) {
        if (DISABLE_CURSOR_TARGETING) {
            return false;
        }
        if (!shouldHandlePlayer(playerRef) || packet.mouseButton == null) {
            return false;
        }
        if (packet.mouseButton.mouseButtonType != MouseButtonType.Right || packet.mouseButton.state != MouseButtonState.Pressed) {
            return false;
        }

        Ref<EntityStore> playerEntityRef = playerRef.getReference();
        if (playerEntityRef == null || !playerEntityRef.isValid()) {
            return false;
        }

        Store<EntityStore> store = playerEntityRef.getStore();
        World world = store.getExternalData().getWorld();
        world.execute(() -> {
            boolean isHealer = CombatTargetingUtil.isActiveHeroHealer(store, playerEntityRef);
            Ref<EntityStore> targetRef = null;
            if (packet.worldInteraction != null && packet.worldInteraction.entityId >= 0) {
                targetRef = store.getExternalData().getRefFromNetworkId(packet.worldInteraction.entityId);
            }
            
            if (targetRef == null || !isValidTarget(store, playerEntityRef, targetRef, isHealer)) {
                targetRef = resolveLookTargetRef(store, playerEntityRef, isHealer);
            }
            
            if (targetRef != null && isValidTarget(store, playerEntityRef, targetRef, isHealer)) {
                LOGGER.at(Level.INFO).log(
                    "right click target assignment player=%s target=%s healer=%s",
                    playerEntityRef,
                    targetRef,
                    isHealer
                );
                TrackedSummonStore.setTargetForActivePartySlot(playerEntityRef, targetRef, store);
                RunPartyHudSystem.requestImmediateRefresh(playerRef.getUuid());
            }
        });
        return true;
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

    private static boolean isRoleSwapChain(SyncInteractionChain chain) {
        if (chain == null || chain.interactionType != InteractionType.SwapFrom || !chain.initial || chain.data == null) {
            return false;
        }

        int roleSlot = chain.data.targetSlot + 1;
        return roleSlot >= 1 && roleSlot <= 3;
    }

    @Nonnull
    private static String describeChain(@Nullable SyncInteractionChain chain) {
        if (chain == null) {
            return "null";
        }
        return "type=" + chain.interactionType
            + " initial=" + chain.initial
            + " chainId=" + chain.chainId
            + " forkedId=" + chain.forkedId
            + " targetSlot=" + (chain.data == null ? null : chain.data.targetSlot)
            + " entityId=" + (chain.data == null ? null : chain.data.entityId);
    }

    private static boolean isTargetAssignmentInput(SyncInteractionChain chain) {
        return chain != null
            && chain.initial
            && (chain.interactionType == InteractionType.Secondary || chain.interactionType == InteractionType.Use);
    }

    private static void handleTargetAssignment(@Nonnull PlayerRef playerRef, @Nonnull SyncInteractionChain chain) {
        if (DISABLE_CURSOR_TARGETING) {
            return;
        }
        Ref<EntityStore> playerEntityRef = playerRef.getReference();
        if (playerEntityRef == null || !playerEntityRef.isValid()) {
            return;
        }

        Store<EntityStore> store = playerEntityRef.getStore();
        World world = store.getExternalData().getWorld();
        world.execute(() -> {
            boolean isHealer = CombatTargetingUtil.isActiveHeroHealer(store, playerEntityRef);
            Ref<EntityStore> targetRef = resolveTargetRef(store, playerEntityRef, chain, isHealer);
            
            if (targetRef != null && isValidTarget(store, playerEntityRef, targetRef, isHealer)) {
                LOGGER.at(Level.INFO).log(
                    "target assignment chain player=%s target=%s healer=%s",
                    playerEntityRef,
                    targetRef,
                    isHealer
                );
                TrackedSummonStore.setTargetForActivePartySlot(playerEntityRef, targetRef, store);
                RunPartyHudSystem.requestImmediateRefresh(playerRef.getUuid());
            }
        });
    }

    @Nullable
    private static Ref<EntityStore> resolveTargetRef(
        @Nonnull Store<EntityStore> store,
        @Nonnull Ref<EntityStore> playerEntityRef,
        @Nonnull SyncInteractionChain chain,
        boolean isHealer
    ) {
        if (chain.data != null && chain.data.entityId >= 0) {
            Ref<EntityStore> targetRef = store.getExternalData().getRefFromNetworkId(chain.data.entityId);
            if (isValidTarget(store, playerEntityRef, targetRef, isHealer)) {
                return targetRef;
            }
        }

        LevelSessionManager.ActiveRunSnapshot snapshot = LevelSessionManager.get().getSnapshot();
        if (snapshot == null || snapshot.runWorldUuid() == null) {
            return null;
        }
        LevelRunCombatStore.CombatWorldState combatState = LevelRunCombatStore.get().getWorld(snapshot.runWorldUuid());
        if (combatState == null) {
            return null;
        }

        return chooseLookTargetFromCombatState(store, playerEntityRef, combatState, isHealer);
    }

    @Nullable
    private static Ref<EntityStore> resolveLookTargetRef(
        @Nonnull Store<EntityStore> store, 
        @Nonnull Ref<EntityStore> playerEntityRef,
        boolean isHealer
    ) {
        LevelSessionManager.ActiveRunSnapshot snapshot = LevelSessionManager.get().getSnapshot();
        if (snapshot == null || snapshot.runWorldUuid() == null) {
            return null;
        }
        LevelRunCombatStore.CombatWorldState combatState = LevelRunCombatStore.get().getWorld(snapshot.runWorldUuid());
        if (combatState == null) {
            return null;
        }
        return chooseLookTargetFromCombatState(store, playerEntityRef, combatState, isHealer);
    }

    @Nullable
    private static Ref<EntityStore> chooseLookTargetFromCombatState(
        @Nonnull Store<EntityStore> store,
        @Nonnull Ref<EntityStore> playerEntityRef,
        @Nonnull LevelRunCombatStore.CombatWorldState combatState,
        boolean isHealer
    ) {
        List<Ref<EntityStore>> potentialTargets = new ArrayList<>(combatState.activeEnemies());
        if (isHealer) {
            for (UUID ownerUuid : combatState.participantPlayerUuids()) {
                PlayerRef ownerRef = Universe.get().getPlayer(ownerUuid);
                if (ownerRef != null && ownerRef.getReference() != null) {
                    potentialTargets.add(ownerRef.getReference());
                    potentialTargets.addAll(TrackedSummonStore.getTrackedSnapshot(ownerRef.getReference()));
                }
            }
        }
        return FocusTargetHighlightSystem.chooseVisibleTargetUnderCrosshair(store, playerEntityRef, potentialTargets);
    }

    private static void handleRoleSwap(@Nonnull PlayerRef playerRef, @Nonnull SyncInteractionChain chain) {
        Ref<EntityStore> playerEntityRef = playerRef.getReference();
        if (playerEntityRef == null || !playerEntityRef.isValid()) {
            return;
        }

        Store<EntityStore> store = playerEntityRef.getStore();
        World world = store.getExternalData().getWorld();
        int requestedHotbarSlot = chain.data.targetSlot;
        int requestedRoleSlot = requestedHotbarSlot + 1;
        LOGGER.at(Level.INFO).log(
            "handleRoleSwap scheduled player=%s requestedHotbarSlot=%s requestedRoleSlot=%s",
            playerEntityRef,
            requestedHotbarSlot,
            requestedRoleSlot
        );
        world.execute(() -> {
            LOGGER.at(Level.INFO).log(
                "handleRoleSwap running player=%s requestedRoleSlot=%s state=%s",
                playerEntityRef,
                requestedRoleSlot,
                TrackedSummonStore.describePartyState(playerEntityRef, store)
            );
            playerRef.getPacketHandler().writeNoCache(new CancelInteractionChain(chain.chainId, chain.forkedId));

            Player player = store.getComponent(playerEntityRef, Player.getComponentType());
            if (player == null) {
                LOGGER.at(Level.WARNING).log("handleRoleSwap abort: missing Player component player=%s", playerEntityRef);
                return;
            }

            SwapResult result = trySwapToRole(store, playerEntityRef, requestedRoleSlot);
            LOGGER.at(Level.INFO).log(
                "handleRoleSwap result=%s requestedRoleSlot=%s state=%s",
                result,
                requestedRoleSlot,
                TrackedSummonStore.describePartyState(playerEntityRef, store)
            );
            int selectedHotbarSlot = NEUTRAL_HOTBAR_SLOT;

            player.getInventory().setActiveHotbarSlot(playerEntityRef, (byte)selectedHotbarSlot, store);
            playerRef.getPacketHandler().writeNoCache(new SetActiveSlot(Inventory.HOTBAR_SECTION_ID, selectedHotbarSlot));
            RunPartyHudSystem.requestImmediateRefresh(playerRef.getUuid());
        });
    }

    private static void cancelInteraction(@Nonnull PlayerRef playerRef, @Nonnull SyncInteractionChain chain) {
        playerRef.getPacketHandler().writeNoCache(new CancelInteractionChain(chain.chainId, chain.forkedId));
    }

    private static boolean shouldHandlePlayer(PlayerRef playerRef) {
        LevelSessionManager.ActiveRunSnapshot snapshot = LevelSessionManager.get().getSnapshot();
        if (snapshot == null || snapshot.runWorldUuid() == null || !snapshot.participantPlayerUuids().contains(playerRef.getUuid())) {
            return false;
        }
        UUID worldId = playerRef.getWorldUuid();
        return worldId != null
            && snapshot.runWorldUuid().equals(worldId)
            && TrackedSummonStore.getActivePartySlot(playerRef) != null;
    }

    private static SwapResult trySwapToRole(
        @Nonnull Store<EntityStore> store,
        @Nonnull Ref<EntityStore> playerRef,
        int roleSlot
    ) {
        Integer currentRoleSlot = TrackedSummonStore.getActivePartySlot(playerRef);
        LOGGER.at(Level.INFO).log(
            "trySwapToRole start player=%s currentRoleSlot=%s targetRoleSlot=%s state=%s",
            playerRef,
            currentRoleSlot,
            roleSlot,
            TrackedSummonStore.describePartyState(playerRef, store)
        );
        if (currentRoleSlot == null || currentRoleSlot == roleSlot) {
            SwapResult result = currentRoleSlot != null && currentRoleSlot == roleSlot ? SwapResult.ALREADY_THERE : SwapResult.NO_TRACKING;
            LOGGER.at(Level.WARNING).log("trySwapToRole stop=%s", result);
            return result;
        }
        if (TrackedSummonStore.isPartySlotOccupiedByOther(playerRef, roleSlot)) {
            LOGGER.at(Level.WARNING).log("trySwapToRole stop=BLOCKED_OCCUPIED targetRoleSlot=%s", roleSlot);
            return SwapResult.BLOCKED_OCCUPIED;
        }

        Ref<EntityStore> npcRef = TrackedSummonStore.getNpcForUtilitySlot(playerRef, roleSlot);
        if (npcRef == null || !npcRef.isValid()) {
            LOGGER.at(Level.WARNING).log("trySwapToRole stop=NO_CARRIER targetRoleSlot=%s npc=%s", roleSlot, npcRef);
            return SwapResult.NO_CARRIER;
        }

        TrackedSummonStore.RoleAppearance targetAppearance = TrackedSummonStore.getPartyAppearance(playerRef, roleSlot);
        TrackedSummonStore.RoleAppearance previousAppearance = TrackedSummonStore.getPartyAppearance(playerRef, currentRoleSlot);
        String failure;
        try {
            failure = NpcSwapService.swapWithNpc(store, playerRef, npcRef);
        } catch (RuntimeException e) {
            LOGGER.at(Level.SEVERE).log(
                "trySwapToRole swapWithNpc threw player=%s npc=%s error=%s",
                playerRef,
                npcRef,
                e.toString()
            );
            return SwapResult.SWAP_FAILED;
        }
        if (failure != null) {
            LOGGER.at(Level.WARNING).log("trySwapToRole swapWithNpc failed: %s", failure);
            return SwapResult.SWAP_FAILED;
        }

        TrackedSummonStore.applyAppearance(store, playerRef, targetAppearance);
        TrackedSummonStore.applyAppearance(store, npcRef, previousAppearance);
        Ref<EntityStore> carrierRef = rebuildCarrierRole(store, playerRef, npcRef, currentRoleSlot);
        if (carrierRef == null || !carrierRef.isValid()) {
            LOGGER.at(Level.WARNING).log("trySwapToRole stop=SWAP_FAILED rebuild carrier=%s", carrierRef);
            return SwapResult.SWAP_FAILED;
        }
        TrackedSummonStore.applyAppearance(store, carrierRef, previousAppearance);

        boolean moved = TrackedSummonStore.movePlayerToPartySlot(playerRef, roleSlot, npcRef, carrierRef);
        LOGGER.at(Level.INFO).log(
            "trySwapToRole movePlayerToPartySlot moved=%s oldCarrier=%s rebuiltCarrier=%s",
            moved,
            npcRef,
            carrierRef
        );
        return moved ? SwapResult.SWAPPED : SwapResult.SWAP_FAILED;
    }

    @Nullable
    private static Ref<EntityStore> rebuildCarrierRole(
        @Nonnull Store<EntityStore> store,
        @Nonnull Ref<EntityStore> playerRef,
        @Nonnull Ref<EntityStore> carrierRef,
        int vacatedRoleSlot
    ) {
        Integer vacatedRoleIndex = TrackedSummonStore.getPartyRoleIndex(playerRef, vacatedRoleSlot);
        if (vacatedRoleIndex == null) {
            LOGGER.at(Level.WARNING).log(
                "rebuildCarrierRole skipped: no vacatedRoleIndex player=%s vacatedSlot=%s carrier=%s",
                playerRef,
                vacatedRoleSlot,
                carrierRef
            );
            return carrierRef;
        }

        LOGGER.at(Level.INFO).log(
            "rebuildCarrierRole player=%s carrier=%s vacatedSlot=%s vacatedRoleIndex=%s",
            playerRef,
            carrierRef,
            vacatedRoleSlot,
            vacatedRoleIndex
        );
        return NpcCarrierRoleService.rebuildCarrierRole(store, carrierRef, vacatedRoleIndex);
    }

    private enum SwapResult {
        SWAPPED,
        ALREADY_THERE,
        BLOCKED_OCCUPIED,
        NO_CARRIER,
        NO_TRACKING,
        SWAP_FAILED
    }

    private static final class DisabledLogger {
        private static final Api API = new Api();

        private Api at(Level level) {
            return API;
        }
    }

    private static final class Api {
        private void log(String message, Object... args) {
        }
    }
}
