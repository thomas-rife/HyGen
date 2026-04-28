package com.example.exampleplugin.commands;

import com.example.exampleplugin.npc.TrackedSummonStore;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.List;

public class ClearNpcCommand extends AbstractPlayerCommand {
    public ClearNpcCommand() {
        super("clearnpc", "Remove tracked NPCs spawned by /summonNPC");
    }

    @Override
    protected void execute(
        @Nonnull CommandContext context,
        @Nonnull Store<EntityStore> store,
        @Nonnull Ref<EntityStore> ref,
        @Nonnull PlayerRef playerRef,
        @Nonnull World world
    ) {
        List<Ref<EntityStore>> tracked = TrackedSummonStore.getTrackedSnapshot(ref);
        int removed = 0;
        for (Ref<EntityStore> npcRef : tracked) {
            if (npcRef != null && npcRef.isValid() && npcRef.getStore() == store) {
                store.removeEntity(npcRef, RemoveReason.REMOVE);
                removed++;
            }
        }
        TrackedSummonStore.clearTracking(ref);
        context.sendMessage(Message.raw("Removed " + removed + " tracked NPC(s)."));
    }
}
