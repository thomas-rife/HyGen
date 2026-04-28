package com.example.exampleplugin.npc;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.modules.entity.repulsion.Repulsion;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.components.Timers;
import com.hypixel.hytale.server.npc.components.messaging.BeaconSupport;
import com.hypixel.hytale.server.npc.components.messaging.NPCBlockEventSupport;
import com.hypixel.hytale.server.npc.components.messaging.NPCEntityEventSupport;
import com.hypixel.hytale.server.npc.components.messaging.PlayerBlockEventSupport;
import com.hypixel.hytale.server.npc.components.messaging.PlayerEntityEventSupport;
import com.hypixel.hytale.server.npc.decisionmaker.stateevaluator.StateEvaluator;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.valuestore.ValueStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.logging.Level;

public final class NpcCarrierRoleService {
    private static final HytaleLogger LOGGER = HytaleLogger.get("Battleheart|CarrierRole");

    private NpcCarrierRoleService() {
    }

    @Nullable
    public static Ref<EntityStore> rebuildCarrierRole(
        @Nonnull Store<EntityStore> store,
        @Nonnull Ref<EntityStore> carrierRef,
        int roleIndex
    ) {
        LOGGER.at(Level.INFO).log("rebuildCarrierRole start carrier=%s roleIndex=%s", carrierRef, roleIndex);
        NPCEntity carrier = store.getComponent(carrierRef, NPCEntity.getComponentType());
        if (carrier == null || carrier.getRole() == null) {
            LOGGER.at(Level.WARNING).log(
                "rebuildCarrierRole skipped: carrierComponent=%s role=%s",
                carrier != null,
                carrier == null ? null : carrier.getRole()
            );
            return carrierRef;
        }
        float healthPercent = EntityHealthUtil.readHealthPercent(store, carrierRef);

        Holder<EntityStore> holder;
        try {
            holder = store.removeEntity(carrierRef, RemoveReason.UNLOAD);
        } catch (RuntimeException e) {
            LOGGER.at(Level.SEVERE).log("rebuildCarrierRole removeEntity threw carrier=%s error=%s", carrierRef, e.toString());
            return null;
        }
        NPCEntity holderNpc = holder.getComponent(NPCEntity.getComponentType());
        if (holderNpc == null) {
            LOGGER.at(Level.WARNING).log("rebuildCarrierRole failed: holder missing NPCEntity carrier=%s", carrierRef);
            return null;
        }

        holderNpc.setRole(null);
        holder.tryRemoveComponent(BeaconSupport.getComponentType());
        holder.tryRemoveComponent(PlayerBlockEventSupport.getComponentType());
        holder.tryRemoveComponent(NPCBlockEventSupport.getComponentType());
        holder.tryRemoveComponent(PlayerEntityEventSupport.getComponentType());
        holder.tryRemoveComponent(NPCEntityEventSupport.getComponentType());
        holder.tryRemoveComponent(Timers.getComponentType());
        holder.tryRemoveComponent(StateEvaluator.getComponentType());
        holder.tryRemoveComponent(ValueStore.getComponentType());
        holder.tryRemoveComponent(Repulsion.getComponentType());
        holderNpc.setRoleName(NPCPlugin.get().getName(roleIndex));
        holderNpc.setRoleIndex(roleIndex);

        try {
            Ref<EntityStore> rebuiltRef = store.addEntity(holder, AddReason.LOAD);
            if (rebuiltRef != null) {
                EntityHealthUtil.setHealthPercentOfMax(store, rebuiltRef, healthPercent);
            }
            LOGGER.at(Level.INFO).log(
                "rebuildCarrierRole complete oldCarrier=%s rebuilt=%s roleName=%s healthPercent=%s",
                carrierRef,
                rebuiltRef,
                NPCPlugin.get().getName(roleIndex),
                healthPercent
            );
            return rebuiltRef;
        } catch (RuntimeException e) {
            LOGGER.at(Level.SEVERE).log("rebuildCarrierRole addEntity threw carrier=%s error=%s", carrierRef, e.toString());
            return null;
        }
    }
}
