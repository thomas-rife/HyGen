package com.example.exampleplugin.npc;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * A modular interface for all character abilities.
 */
public interface HeroAbility {
    /**
     * Gets the role ID associated with this ability (e.g., "HyGen_Companion_Vanguard").
     */
    @Nonnull
    String getRoleId();

    /**
     * Attempts to activate the ability for the given player.
     * 
     * @param store The entity store.
     * @param playerRef The reference to the player entity invoking the ability.
     * @return true if the ability was successfully activated, false if on cooldown or blocked.
     */
    boolean tryActivate(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> playerRef);

    /**
     * Gets the cooldown progress for the given party slot, from 0.0 (just used) to 1.0 (ready).
     * 
     * @param slot The party slot of the hero.
     * @return A float representing cooldown progress.
     */
    float getCooldownProgress(int slot);

    /**
     * Returns true if the ability is currently active or loaded for the given party slot.
     * This is primarily used for UI representation (e.g., glowing icon).
     * 
     * @param slot The party slot of the hero.
     * @return true if the ability is active.
     */
    boolean isAbilityActive(int slot);

    /**
     * Optional entity effect to apply to the hero while the ability is active.
     * This is intended for in-world activation feedback.
     */
    @Nullable
    default String getActivationEffectId() {
        return null;
    }

    /**
     * Optional tick method for abilities that need to process logic over time or clean up state.
     * 
     * @param store The entity store.
     * @param now The current time in milliseconds.
     */
    default void tick(@Nonnull Store<EntityStore> store, long now) {}
}
