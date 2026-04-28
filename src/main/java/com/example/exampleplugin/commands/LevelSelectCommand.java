package com.example.exampleplugin.commands;

import com.example.exampleplugin.levels.LevelConfigStore;
import com.example.exampleplugin.levels.ui.LevelSelectPage;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

public class LevelSelectCommand extends AbstractPlayerCommand {
    public LevelSelectCommand() {
        super("levels", "Open the level selector menu");
    }

    @Override
    protected void execute(
        @Nonnull CommandContext context,
        @Nonnull Store<EntityStore> store,
        @Nonnull Ref<EntityStore> ref,
        @Nonnull PlayerRef playerRef,
        @Nonnull World world
    ) {
        // Ensure levels file exists before opening UI.
        LevelConfigStore.get().ensureLoaded();
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            return;
        }
        player.getPageManager().openCustomPage(ref, store, new LevelSelectPage(playerRef));
    }
}
