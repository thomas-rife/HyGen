package com.example.exampleplugin.commands;

import com.example.exampleplugin.levels.LevelConfigStore;
import com.example.exampleplugin.levels.LevelSessionManager;
import com.example.exampleplugin.levels.model.LevelDefinition;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.List;

public class StartLevelCommand extends AbstractPlayerCommand {
    private final OptionalArg<String> levelArg = this.withOptionalArg("levelId", "Level id to start", ArgTypes.STRING);

    public StartLevelCommand() {
        super("startlevel", "Start a level run (defaults to first configured level)");
    }

    @Override
    protected void execute(
        @Nonnull CommandContext context,
        @Nonnull Store<EntityStore> store,
        @Nonnull Ref<EntityStore> ref,
        @Nonnull PlayerRef playerRef,
        @Nonnull World world
    ) {
        List<LevelDefinition> levels = LevelConfigStore.get().getLevels();
        if (levels.isEmpty()) {
            context.sendMessage(Message.raw("No levels configured."));
            return;
        }

        String levelId = this.levelArg.provided(context)
            ? this.levelArg.get(context)
            : levels.get(0).levelId;

        if (levelId == null || levelId.isBlank()) {
            context.sendMessage(Message.raw("Invalid level id."));
            return;
        }

        LevelSessionManager.get().startLevelForPlayer(playerRef, levelId).whenComplete((result, throwable) -> {
            if (throwable != null) {
                String reason = throwable.getCause() != null ? throwable.getCause().getMessage() : throwable.getMessage();
                playerRef.sendMessage(Message.raw("Failed to start level: " + reason));
                return;
            }
            playerRef.sendMessage(Message.raw("Started " + result.levelName() + " on run world " + result.runWorldName() + "."));
        });
    }
}
