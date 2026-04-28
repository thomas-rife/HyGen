package com.example.exampleplugin.commands;

import com.example.exampleplugin.levels.LevelSessionManager;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

public class FinishRunCommand extends AbstractPlayerCommand {
    public FinishRunCommand() {
        super("finish", "Finish the active run as victory and clean up the run world");
    }

    @Override
    protected void execute(
        @Nonnull CommandContext context,
        @Nonnull Store<EntityStore> store,
        @Nonnull Ref<EntityStore> ref,
        @Nonnull PlayerRef playerRef,
        @Nonnull World world
    ) {
        LevelSessionManager.get().finishActiveRun(true).whenComplete((result, throwable) -> {
            if (throwable != null) {
                String reason = throwable.getCause() != null ? throwable.getCause().getMessage() : throwable.getMessage();
                playerRef.sendMessage(Message.raw("Failed to finish run: " + reason));
                return;
            }
            playerRef.sendMessage(Message.raw(result.message()));
        });
    }
}
