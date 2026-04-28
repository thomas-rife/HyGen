package com.example.exampleplugin.commands;

import com.example.exampleplugin.levels.HeroSelectionStore;
import com.example.exampleplugin.levels.HeroAppearanceLibrary;
import com.example.exampleplugin.levels.LevelSessionManager;
import com.example.exampleplugin.npc.BattleheartCameraService;
import com.example.exampleplugin.npc.EntityHealthUtil;
import com.example.exampleplugin.npc.TrackedSummonStore;
import com.example.exampleplugin.npc.NpcAttitudeService;
import com.example.exampleplugin.npc.NpcSwapService;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.function.consumer.TriConsumer;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.protocol.PlayerSkin;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.cosmetics.CosmeticsModule;
import com.hypixel.hytale.server.core.entity.Frozen;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerSkinComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.protocol.packets.inventory.SetActiveSlot;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.asset.builder.BuilderInfo;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import it.unimi.dsi.fastutil.Pair;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Level;

public class SummonNpcTripleCommand extends AbstractPlayerCommand {
    private static final HytaleLogger LOGGER = HytaleLogger.get("HyGen|PartySpawn");
    private static final int NEUTRAL_HOTBAR_SLOT = 3;
    private static final List<String> DEFAULT_PARTY_ROLES = List.of(
        "HyGen_Companion_Vanguard",
        "HyGen_Companion_Archer",
        "HyGen_Companion_Support"
    );

    private final OptionalArg<String> roleArg = this.withOptionalArg("role", "Optional NPC role name", ArgTypes.STRING);

    public SummonNpcTripleCommand() {
        super("summonNPC3", "Spawn 3 tracked NPCs mapped to utility slots 0/1/2");
    }

    @Override
    protected void execute(
        @Nonnull CommandContext context,
        @Nonnull Store<EntityStore> store,
        @Nonnull Ref<EntityStore> ref,
        @Nonnull PlayerRef playerRef,
        @Nonnull World world
    ) {
        NPCPlugin npcPlugin = NPCPlugin.get();
        LOGGER.at(Level.INFO).log(
            "/summonNPC3 start player=%s entity=%s world=%s overrideProvided=%s",
            playerRef.getUuid(),
            ref,
            world.getWorldConfig().getUuid(),
            this.roleArg.provided(context)
        );
        List<RoleSpawnPlan> spawnPlans = this.roleArg.provided(context)
            ? buildOverrideSpawnPlans(npcPlugin, this.roleArg.get(context))
            : buildDefaultSpawnPlans(npcPlugin, playerRef.getUuid());
        LOGGER.at(Level.INFO).log("/summonNPC3 plans=%s", spawnPlans);
        if (spawnPlans.isEmpty()) {
            LOGGER.at(Level.WARNING).log("/summonNPC3 abort: no spawn plans");
            context.sendMessage(Message.raw("No NPC roles are available to spawn."));
            return;
        }
        List<Ref<EntityStore>> playerSlotRefs = resolvePlayerSlotRefs(ref, world, store);
        LOGGER.at(Level.INFO).log("/summonNPC3 playerSlotRefs=%s", playerSlotRefs);

        TransformComponent playerTransform = store.getComponent(ref, TransformComponent.getComponentType());
        HeadRotation headRotation = store.getComponent(ref, HeadRotation.getComponentType());
        if (playerTransform == null || headRotation == null) {
            LOGGER.at(Level.WARNING).log(
                "/summonNPC3 abort: transform=%s headRotation=%s",
                playerTransform != null,
                headRotation != null
            );
            context.sendMessage(Message.raw("Could not read your current position."));
            return;
        }

        removeTrackedCompanions(store, ref);
        TrackedSummonStore.clearTracking(ref);
        TrackedSummonStore.configurePartyPlayers(ref, playerSlotRefs);
        LOGGER.at(Level.INFO).log("/summonNPC3 after configure: %s", TrackedSummonStore.describePartyState(ref, store));
        syncHotbarsToCurrentRoles(store, playerSlotRefs);

        Vector3f playerRot = headRotation.getRotation();
        double yaw = playerRot.getYaw();
        double forward = 2.25;
        double spacing = 1.5;
        int spawned = 0;

        for (int partySlot = 1; partySlot <= spawnPlans.size() && partySlot <= 3; partySlot++) {
            RoleSpawnPlan spawnPlan = spawnPlans.get(partySlot - 1);
            TrackedSummonStore.setPartyRoleIndex(ref, partySlot, spawnPlan.roleIndex());
            LOGGER.at(Level.INFO).log(
                "/summonNPC3 slot=%s spawning role=%s index=%s",
                partySlot,
                spawnPlan.roleName(),
                spawnPlan.roleIndex()
            );
            double side = (partySlot - 2) * spacing;
            Vector3d spawnPos = new Vector3d(playerTransform.getPosition());
            spawnPos.x += Math.sin(yaw) * forward + Math.cos(yaw) * side;
            spawnPos.y += 2.0;
            spawnPos.z += Math.cos(yaw) * forward - Math.sin(yaw) * side;

            PlayerSkin playerSkin = HeroAppearanceLibrary.getFixedSkin(spawnPlan.roleName());
            Model playerModel = CosmeticsModule.get().createModel(playerSkin);
            TriConsumer<NPCEntity, Ref<EntityStore>, Store<EntityStore>> postSpawn = (npcEntity, npcRef, entityStore) -> {
                npcEntity.setInitialModelScale(1.0f);
                entityStore.putComponent(npcRef, PlayerSkinComponent.getComponentType(), new PlayerSkinComponent(playerSkin));
                if (entityStore.getComponent(npcRef, Frozen.getComponentType()) != null) {
                    entityStore.removeComponent(npcRef, Frozen.getComponentType());
                }
            };

            Pair<Ref<EntityStore>, NPCEntity> npcPair = npcPlugin.spawnEntity(
                store,
                spawnPlan.roleIndex(),
                spawnPos,
                new Vector3f(0.0f, playerRot.getYaw(), 0.0f),
                playerModel,
                postSpawn
            );

            if (npcPair != null) {
                Ref<EntityStore> roleCarrierRef = npcPair.first();
                TrackedSummonStore.setPartyAppearance(ref, partySlot, playerSkin);
                LOGGER.at(Level.INFO).log(
                    "/summonNPC3 slot=%s spawned carrier=%s role=%s",
                    partySlot,
                    roleCarrierRef,
                    spawnPlan.roleName()
                );
                if (partySlot <= playerSlotRefs.size()) {
                    Ref<EntityStore> rolePlayerRef = playerSlotRefs.get(partySlot - 1);
                    LOGGER.at(Level.INFO).log(
                        "/summonNPC3 slot=%s immediate swap rolePlayer=%s carrier=%s",
                        partySlot,
                        rolePlayerRef,
                        roleCarrierRef
                    );
                    String failure = NpcSwapService.swapWithNpc(store, rolePlayerRef, roleCarrierRef);
                    if (failure == null) {
                        LOGGER.at(Level.INFO).log("/summonNPC3 slot=%s immediate swap ok", partySlot);
                        if (roleCarrierRef.isValid() && roleCarrierRef.getStore() == store) {
                            store.removeEntity(roleCarrierRef, RemoveReason.REMOVE);
                            LOGGER.at(Level.INFO).log("/summonNPC3 slot=%s removed temporary carrier=%s", partySlot, roleCarrierRef);
                        }
                    } else {
                        LOGGER.at(Level.WARNING).log(
                            "/summonNPC3 slot=%s immediate swap failed: %s",
                            partySlot,
                            failure
                        );
                        TrackedSummonStore.trackInSlot(ref, roleCarrierRef, spawnPlan.roleIndex(), partySlot);
                        NpcAttitudeService.configureCompanionAttitudes(store, world, ref, roleCarrierRef);
                    }
                } else {
                    TrackedSummonStore.trackInSlot(ref, roleCarrierRef, spawnPlan.roleIndex(), partySlot);
                    NpcAttitudeService.configureCompanionAttitudes(store, world, ref, roleCarrierRef);
                }
                spawned++;
                LOGGER.at(Level.INFO).log(
                    "/summonNPC3 after slot=%s state=%s",
                    partySlot,
                    TrackedSummonStore.describePartyState(ref, store)
                );
            } else {
                LOGGER.at(Level.WARNING).log(
                    "/summonNPC3 slot=%s spawnEntity returned null for role=%s",
                    partySlot,
                    spawnPlan.roleName()
                );
            }
        }

        if (spawned == 0) {
            LOGGER.at(Level.WARNING).log("/summonNPC3 abort: spawned=0");
            context.sendMessage(Message.raw("Failed to spawn the HyGen companion party."));
            return;
        }

        syncHotbarsToCurrentRoles(store, playerSlotRefs);
        BattleheartCameraService.applyPreferredCamera(playerRef, true);
        LOGGER.at(Level.INFO).log(
            "/summonNPC3 complete spawned=%s finalState=%s",
            spawned,
            TrackedSummonStore.describePartyState(ref, store)
        );

        context.sendMessage(
            Message.raw(
                "Spawned "
                    + spawned
                    + " party companion(s). Hotbar slots 1/2/3 map to party roles."
            )
        );
    }

    private static void removeTrackedCompanions(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ownerRef) {
        List<Ref<EntityStore>> tracked = TrackedSummonStore.getTrackedSnapshot(ownerRef);
        LOGGER.at(Level.INFO).log("removeTrackedCompanions owner=%s count=%s", ownerRef, tracked.size());
        for (Ref<EntityStore> npcRef : tracked) {
            if (npcRef != null && npcRef.isValid() && npcRef.getStore() == store) {
                store.removeEntity(npcRef, RemoveReason.REMOVE);
                LOGGER.at(Level.INFO).log("removeTrackedCompanions removed=%s", npcRef);
            }
        }
    }

    private static void syncHotbarsToCurrentRoles(@Nonnull Store<EntityStore> store, @Nonnull List<Ref<EntityStore>> playerRefs) {
        for (Ref<EntityStore> playerEntityRef : playerRefs) {
            if (playerEntityRef == null || !playerEntityRef.isValid() || playerEntityRef.getStore() != store) {
                continue;
            }

            Integer activeRoleSlot = TrackedSummonStore.getActivePartySlot(playerEntityRef);
            if (activeRoleSlot == null) {
                continue;
            }

            Player player = store.getComponent(playerEntityRef, Player.getComponentType());
            PlayerRef playerRef = store.getComponent(playerEntityRef, PlayerRef.getComponentType());
            if (player == null || playerRef == null) {
                continue;
            }

            int hotbarSlot = NEUTRAL_HOTBAR_SLOT;
            LOGGER.at(Level.INFO).log(
                "syncHotbarsToCurrentRoles player=%s activeRoleSlot=%s settingHotbar=%s",
                playerEntityRef,
                activeRoleSlot,
                hotbarSlot
            );
            player.getInventory().setActiveHotbarSlot(playerEntityRef, (byte)hotbarSlot, store);
            playerRef.getPacketHandler().writeNoCache(new SetActiveSlot(Inventory.HOTBAR_SECTION_ID, hotbarSlot));
        }
    }

    @Nonnull
    private static List<Ref<EntityStore>> resolvePlayerSlotRefs(
        @Nonnull Ref<EntityStore> ownerRef,
        @Nonnull World world,
        @Nonnull Store<EntityStore> store
    ) {
        LevelSessionManager.ActiveRunSnapshot snapshot = LevelSessionManager.get().getSnapshot();
        if (snapshot == null || snapshot.runWorldUuid() == null || !snapshot.runWorldUuid().equals(world.getWorldConfig().getUuid())) {
            return List.of(ownerRef);
        }

        List<Ref<EntityStore>> refs = new ArrayList<>();
        for (UUID playerUuid : snapshot.participantPlayerUuids()) {
            PlayerRef participantRef = Universe.get().getPlayer(playerUuid);
            if (participantRef == null) {
                continue;
            }
            Ref<EntityStore> entityRef = participantRef.getReference();
            if (entityRef != null && entityRef.isValid() && entityRef.getStore() == store) {
                refs.add(entityRef);
            }
            if (refs.size() >= 3) {
                break;
            }
        }
        if (refs.isEmpty()) {
            refs.add(ownerRef);
        }
        return refs;
    }

    @Nonnull
    private static List<RoleSpawnPlan> buildDefaultSpawnPlans(@Nonnull NPCPlugin npcPlugin, @Nonnull UUID playerUuid) {
        List<RoleSpawnPlan> plans = new ArrayList<>();
        List<String> preferredRoles = HeroSelectionStore.getSelectedRoleIds(playerUuid);
        if (preferredRoles.isEmpty()) {
            preferredRoles = DEFAULT_PARTY_ROLES;
        }
        for (String preferredRole : preferredRoles) {
            RoleSpawnPlan plan = resolveSpawnPlan(npcPlugin, preferredRole);
            if (plan != null) {
                plans.add(plan);
            }
        }
        return plans;
    }

    @Nonnull
    private static List<RoleSpawnPlan> buildOverrideSpawnPlans(@Nonnull NPCPlugin npcPlugin, @Nonnull String requestedRole) {
        RoleSpawnPlan plan = resolveSpawnPlan(npcPlugin, requestedRole);
        if (plan == null) {
            return List.of();
        }
        return List.of(plan, plan, plan);
    }

    @Nullable
    private static RoleSpawnPlan resolveSpawnPlan(@Nonnull NPCPlugin npcPlugin, @Nonnull String desiredRole) {
        List<String> spawnable = npcPlugin.getRoleTemplateNames(true);
        String match = findCaseInsensitive(spawnable, desiredRole);
        if (match == null) {
            return null;
        }
        int roleIndex = npcPlugin.getIndex(match);
        BuilderInfo roleInfo = npcPlugin.getRoleBuilderInfo(roleIndex);
        if (roleInfo == null || !roleInfo.getBuilder().isSpawnable()) {
            return null;
        }
        return new RoleSpawnPlan(match, roleIndex);
    }

    @Nullable
    private static String findCaseInsensitive(@Nonnull List<String> values, @Nonnull String target) {
        String lower = target.toLowerCase(Locale.ROOT);
        for (String value : values) {
            if (value.toLowerCase(Locale.ROOT).equals(lower)) {
                return value;
            }
        }
        return null;
    }

    private record RoleSpawnPlan(@Nonnull String roleName, int roleIndex) {
    }
}
