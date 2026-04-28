package com.example.exampleplugin.commands;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.function.consumer.TriConsumer;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.entity.Frozen;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.asset.builder.BuilderInfo;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.example.exampleplugin.npc.TrackedSummonStore;
import com.example.exampleplugin.npc.NpcAttitudeService;
import com.example.exampleplugin.npc.EntityHealthUtil;
import it.unimi.dsi.fastutil.Pair;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Locale;

public class SummonNpcCommand extends AbstractPlayerCommand {
    private final OptionalArg<String> roleArg = this.withOptionalArg("role", "Optional NPC role name", ArgTypes.STRING);

    public SummonNpcCommand() {
        super("summonNPC", "Spawn a frozen NPC with a player-style model");
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
        String requestedRole = this.roleArg.provided(context) ? this.roleArg.get(context) : pickDefaultRole(npcPlugin);
        if (requestedRole == null || requestedRole.isEmpty()) {
            context.sendMessage(Message.raw("No NPC roles are available to spawn."));
            return;
        }

        int roleIndex = npcPlugin.getIndex(requestedRole);
        BuilderInfo roleInfo = npcPlugin.getRoleBuilderInfo(roleIndex);
        if (roleInfo == null) {
            context.sendMessage(Message.raw("Unknown NPC role: " + requestedRole));
            return;
        }
        if (!roleInfo.getBuilder().isSpawnable()) {
            context.sendMessage(Message.raw("NPC role is abstract and cannot be spawned: " + requestedRole));
            return;
        }

        TransformComponent playerTransform = store.getComponent(ref, TransformComponent.getComponentType());
        HeadRotation headRotation = store.getComponent(ref, HeadRotation.getComponentType());
        if (playerTransform == null || headRotation == null) {
            context.sendMessage(Message.raw("Could not read your current position."));
            return;
        }

        Vector3f playerRot = headRotation.getRotation();
        Vector3d spawnPos = new Vector3d(playerTransform.getPosition());
        double distance = 2.0;
        spawnPos.x += Math.sin(playerRot.getYaw()) * distance;
        spawnPos.z += Math.cos(playerRot.getYaw()) * distance;

        TriConsumer<NPCEntity, Ref<EntityStore>, Store<EntityStore>> postSpawn = (npcEntity, npcRef, entityStore) -> {
            npcEntity.setInitialModelScale(1.0f);
            if (entityStore.getComponent(npcRef, Frozen.getComponentType()) != null) {
                entityStore.removeComponent(npcRef, Frozen.getComponentType());
            }
            EntityHealthUtil.fillHealthToMax(entityStore, npcRef);
        };

        Pair<Ref<EntityStore>, NPCEntity> npcPair = npcPlugin.spawnEntity(
            store,
            roleIndex,
            spawnPos,
            new Vector3f(0.0f, playerRot.getYaw(), 0.0f),
            null,
            postSpawn
        );

        if (npcPair == null) {
            context.sendMessage(Message.raw("Failed to spawn NPC for role: " + requestedRole));
            return;
        }

        EntityHealthUtil.fillHealthToMax(store, npcPair.first());
        TrackedSummonStore.trackInNextSlot(ref, npcPair.first(), roleIndex);
        NpcAttitudeService.configureCompanionAttitudes(store, world, ref, npcPair.first());
        context.sendMessage(Message.raw("Spawned NPC role '" + requestedRole + "' at " + spawnPos.toString() + "."));
    }

    static String pickDefaultRole(@Nonnull NPCPlugin npcPlugin) {
        List<String> spawnable = npcPlugin.getRoleTemplateNames(true);
        if (spawnable.isEmpty()) {
            return null;
        }

        for (String preferred : new String[]{"Test_Group_Sheep", "Test_Bird", "Test_Attack_Bow"}) {
            String match = findCaseInsensitive(spawnable, preferred);
            if (match != null) {
                return match;
            }
        }
        return spawnable.get(0);
    }

    private static String findCaseInsensitive(@Nonnull List<String> values, @Nonnull String target) {
        String lower = target.toLowerCase(Locale.ROOT);
        for (String value : values) {
            if (value.toLowerCase(Locale.ROOT).equals(lower)) {
                return value;
            }
        }
        return null;
    }
}
