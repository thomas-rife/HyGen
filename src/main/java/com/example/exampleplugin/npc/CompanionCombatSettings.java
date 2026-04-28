package com.example.exampleplugin.npc;

public final class CompanionCombatSettings {
    private static volatile boolean combatEnabled = true;

    private CompanionCombatSettings() {
    }

    public static boolean isCombatEnabled() {
        return combatEnabled;
    }

    public static void setCombatEnabled(boolean enabled) {
        combatEnabled = enabled;
    }
}
