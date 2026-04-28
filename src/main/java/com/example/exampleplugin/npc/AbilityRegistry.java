package com.example.exampleplugin.npc;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A central registry for all hero abilities.
 */
public final class AbilityRegistry {
    private static final ConcurrentHashMap<String, HeroAbility> ABILITIES = new ConcurrentHashMap<>();

    private AbilityRegistry() {
    }

    /**
     * Registers a new hero ability.
     */
    public static void register(@Nonnull HeroAbility ability) {
        ABILITIES.put(ability.getRoleId(), ability);
    }

    /**
     * Gets an ability by its role ID.
     */
    @Nullable
    public static HeroAbility getAbility(@Nonnull String roleId) {
        return ABILITIES.get(roleId);
    }

    /**
     * Gets all registered abilities.
     */
    @Nonnull
    public static Collection<HeroAbility> getAllAbilities() {
        return ABILITIES.values();
    }
}
