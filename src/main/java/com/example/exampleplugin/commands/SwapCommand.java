package com.example.exampleplugin.commands;

import com.example.exampleplugin.npc.TrackedSummonStore;
import com.example.exampleplugin.npc.NpcSwapService;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

public class SwapCommand extends AbstractPlayerCommand {
    public SwapCommand() {
        super("swap", "Swap your skin/model and position with the tracked summoned NPC");
    }

    @Override
    protected void execute(
        @Nonnull CommandContext context,
        @Nonnull Store<EntityStore> store,
        @Nonnull Ref<EntityStore> ref,
        @Nonnull PlayerRef playerRef,
        @Nonnull World world
    ) {
        Ref<EntityStore> npcRef = TrackedSummonStore.getActiveNpcRef(ref);
        if (npcRef == null || !npcRef.isValid()) {
            context.sendMessage(Message.raw("No tracked summoned NPC found. Use /summonNPC first."));
            return;
        }
        String failure = NpcSwapService.swapWithNpc(store, ref, npcRef);
        if (failure != null) {
            context.sendMessage(Message.raw(failure));
            return;
        }
        Ref<EntityStore> defaultCarrierRef = TrackedSummonStore.getDefaultCarrierNpcRef(ref);
        TrackedSummonStore.setActiveNpc(ref, npcRef);
        if (npcRef == defaultCarrierRef) {
            TrackedSummonStore.setDefaultCarrierNpcRef(ref, null);
        } else if (defaultCarrierRef == null) {
            TrackedSummonStore.setDefaultCarrierNpcRef(ref, npcRef);
        }
        context.sendMessage(Message.raw("Full swap complete (skin/model/position/rotation)."));
    }
}
