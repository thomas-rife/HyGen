package com.example.exampleplugin.levels;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class HeroSelectionStore {
    private static final ConcurrentHashMap<UUID, List<Hero>> SELECTED_BY_PLAYER = new ConcurrentHashMap<>();
    private static final List<Hero> ALL_HEROES = List.of(
        new Hero("vanguard", "Vanguard", "Frontline defender", "HyGen_Companion_Vanguard", "HyGen_Icon_Vanguard", "#536f91", HeroClass.TANK),
        new Hero("archer", "Archer", "Backline damage", "HyGen_Companion_Archer", "HyGen_Icon_Archer", "#5f7f52", HeroClass.DAMAGE),
        new Hero("barbarian", "Barbarian", "Close-range control", "HyGen_Companion_Barbarian", "HyGen_Icon_Barbarian", "#7d5b4e", HeroClass.DAMAGE),
        new Hero("mage", "Mage", "AoE spells", "HyGen_Companion_Mage", "HyGen_Icon_Mage", "#4f6f72", HeroClass.DAMAGE),
        new Hero("wizard", "Wizard", "Ranged spellcaster", "HyGen_Companion_Wizard", "HyGen_Icon_Wizard", "#8a504b", HeroClass.DAMAGE),
        new Hero("warden", "Warden", "AoE healing", "HyGen_Companion_Warden", "HyGen_Icon_Warden", "#75608c", HeroClass.SUPPORT),
        new Hero("monk", "Monk", "Single-target healer", "HyGen_Companion_Monk", "HyGen_Icon_Monk", "#a19060", HeroClass.SUPPORT),
        new Hero("oracle", "Oracle", "Buffs and shields", "HyGen_Companion_Oracle", "HyGen_Icon_Oracle", "#4d80a1", HeroClass.SUPPORT)
    );
    private static final List<Hero> DEFAULT_SELECTED = List.of(ALL_HEROES.get(0), ALL_HEROES.get(1), ALL_HEROES.get(5));

    private HeroSelectionStore() {
    }

    @Nonnull
    public static synchronized List<Hero> getSelectedHeroes(@Nonnull UUID playerUuid) {
        return List.copyOf(selectedFor(playerUuid));
    }

    @Nonnull
    public static synchronized List<Hero> getAvailableHeroes(@Nonnull UUID playerUuid) {
        List<Hero> selected = selectedFor(playerUuid);
        List<Hero> available = new ArrayList<>();
        for (Hero hero : ALL_HEROES) {
            if (!selected.contains(hero)) {
                available.add(hero);
            }
        }
        return List.copyOf(available);
    }

    @Nonnull
    public static synchronized List<String> getSelectedRoleIds(@Nonnull UUID playerUuid) {
        List<String> roleIds = new ArrayList<>();
        for (Hero hero : selectedFor(playerUuid)) {
            roleIds.add(hero.roleId());
        }
        return List.copyOf(roleIds);
    }

    @Nonnull
    public static synchronized List<String> getSelectedDisplayNames(@Nonnull UUID playerUuid) {
        List<String> names = new ArrayList<>();
        for (Hero hero : selectedFor(playerUuid)) {
            names.add(hero.displayName());
        }
        return List.copyOf(names);
    }

    public static synchronized void dropHero(@Nonnull UUID playerUuid, @Nonnull TargetGrid targetGrid, int targetIndex, @Nullable String sourceItemId) {
        if (sourceItemId == null || targetIndex < 0) {
            return;
        }

        List<Hero> selected = selectedFor(playerUuid);
        Hero sourceHero = findByItemId(sourceItemId);
        if (sourceHero == null) {
            return;
        }

        int sourceSelectedIndex = selected.indexOf(sourceHero);
        if (targetGrid == TargetGrid.SELECTED) {
            if (targetIndex >= selected.size()) {
                return;
            }
            if (sourceSelectedIndex >= 0) {
                Hero targetHero = selected.get(targetIndex);
                selected.set(targetIndex, sourceHero);
                selected.set(sourceSelectedIndex, targetHero);
                return;
            }
            selected.set(targetIndex, sourceHero);
            return;
        }

        if (targetGrid == TargetGrid.AVAILABLE && sourceSelectedIndex >= 0) {
            List<Hero> available = getAvailableHeroes(playerUuid);
            if (targetIndex >= available.size()) {
                return;
            }
            Hero targetHero = available.get(targetIndex);
            if (targetHero != null) {
                selected.set(sourceSelectedIndex, targetHero);
            }
        }
    }

    @Nullable
    private static Hero findByItemId(@Nonnull String itemId) {
        for (Hero hero : ALL_HEROES) {
            if (itemId.equals(hero.itemId())) {
                return hero;
            }
        }
        return null;
    }

    @Nonnull
    private static List<Hero> selectedFor(@Nonnull UUID playerUuid) {
        return SELECTED_BY_PLAYER.computeIfAbsent(playerUuid, ignored -> new ArrayList<>(DEFAULT_SELECTED));
    }

    public enum TargetGrid {
        SELECTED,
        AVAILABLE
    }

    public enum HeroClass {
        TANK,
        DAMAGE,
        SUPPORT
    }

    public record Hero(
        @Nonnull String id,
        @Nonnull String displayName,
        @Nonnull String description,
        @Nonnull String roleId,
        @Nonnull String itemId,
        @Nonnull String backgroundColor,
        @Nonnull HeroClass heroClass
    ) {
    }
}
