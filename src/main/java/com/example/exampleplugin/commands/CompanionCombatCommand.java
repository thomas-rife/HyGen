package com.example.exampleplugin.commands;

import com.example.exampleplugin.npc.CompanionCombatSettings;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class CompanionCombatCommand extends AbstractPlayerCommand {
    public CompanionCombatCommand() {
        super("companioncombat", "Toggle companion combat on/off");
        this.setAllowsExtraArguments(true);
    }

    @Override
    protected void execute(
        @Nonnull CommandContext context,
        @Nonnull Store<EntityStore> store,
        @Nonnull Ref<EntityStore> ref,
        @Nonnull PlayerRef playerRef,
        @Nonnull World world
    ) {
        List<String> args = getPositionalTokens(context);
        String mode = args.isEmpty() ? "toggle" : args.get(0).toLowerCase(Locale.ROOT);
        boolean enabled;
        switch (mode) {
            case "on", "true" -> {
                CompanionCombatSettings.setCombatEnabled(true);
                enabled = true;
            }
            case "off", "false" -> {
                CompanionCombatSettings.setCombatEnabled(false);
                enabled = false;
            }
            default -> {
                enabled = !CompanionCombatSettings.isCombatEnabled();
                CompanionCombatSettings.setCombatEnabled(enabled);
            }
        }
        context.sendMessage(Message.raw("Companion combat: " + (enabled ? "ON" : "OFF")));
    }

    @Nonnull
    private static List<String> getPositionalTokens(@Nonnull CommandContext context) {
        String input = context.getInputString();
        if (input == null || input.isBlank()) {
            return List.of();
        }
        String[] split = input.trim().split("\\s+");
        List<String> tokens = new ArrayList<>();
        for (int i = 1; i < split.length; i++) {
            String token = split[i];
            if (token == null || token.isBlank() || token.startsWith("--")) {
                continue;
            }
            tokens.add(token);
        }
        return tokens;
    }
}
