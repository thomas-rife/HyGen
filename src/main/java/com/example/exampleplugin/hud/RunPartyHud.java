package com.example.exampleplugin.hud;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.ui.Anchor;
import com.hypixel.hytale.server.core.ui.ItemGridSlot;
import com.hypixel.hytale.server.core.ui.PatchStyle;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

public class RunPartyHud extends CustomUIHud {
    private static final int MAX_HERO_SLOTS = 3;
    private static final int HEALTH_BAR_WIDTH = 78;
    private static final int HEALTH_BAR_HEIGHT = 14;
    private static final int COOLDOWN_BAR_WIDTH = 78;
    private static final int COOLDOWN_BAR_HEIGHT = 8;
    private static final float HEALTH_LERP_RATE = 18.0f;
    private static final float COOLDOWN_LERP_RATE = 14.0f;

    public record HeroSlot(
        @Nonnull String name,
        @Nonnull String itemId,
        boolean active,
        boolean occupiedByOtherPlayer,
        float health,
        float maxHealth,
        boolean showCooldown,
        boolean abilityActive,
        float cooldownProgress,
        @Nullable String abilitySummary,
        @Nonnull String heroId,
        boolean dead
    ) {
    }

    private final List<HeroSlot> slots = new ArrayList<>();
    private final float[] visualHealthPercents = new float[MAX_HERO_SLOTS];
    private final float[] visualCooldownPercents = new float[MAX_HERO_SLOTS];
    private long lastRefreshTime = System.currentTimeMillis();
    private boolean visible;

    public RunPartyHud(@Nonnull PlayerRef playerRef) {
        super(playerRef);
        for (int i = 0; i < MAX_HERO_SLOTS; i++) {
            visualHealthPercents[i] = 1.0f;
            visualCooldownPercents[i] = 0.0f;
        }
    }

    public void setHeroSlots(@Nonnull List<HeroSlot> newSlots) {
        this.slots.clear();
        this.slots.addAll(newSlots);
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    @Override
    protected void build(@Nonnull UICommandBuilder ui) {
        if (!this.visible || this.slots.isEmpty()) {
            return;
        }

        ui.append("Hud/SimpleParty/PartyHud.ui");
        writeState(ui);
    }

    public void refresh() {
        UICommandBuilder ui = new UICommandBuilder();
        writeState(ui);
        update(false, ui);
    }

    private void writeState(@Nonnull UICommandBuilder ui) {
        long now = System.currentTimeMillis();
        float dt = Math.min((now - lastRefreshTime) / 1000f, 0.05f);
        lastRefreshTime = now;

        for (int i = 1; i <= MAX_HERO_SLOTS; i++) {
            ui.set("#HeroSlot" + i + ".Visible", false);
            ui.set("#HeroBackActive" + i + ".Visible", false);
            ui.set("#HeroBackAvailable" + i + ".Visible", false);
            ui.set("#HeroBackOccupied" + i + ".Visible", false);
            ui.set("#HeroBackDead" + i + ".Visible", false);
            ui.set("#HeroAbilityGlow" + i + ".Visible", false);
            ui.set("#HeroOverlay" + i + ".Visible", false);
            ui.set("#CooldownBack" + i + ".Visible", false);
            ui.setObject("#HealthBar" + i + ".Anchor", buildBarAnchor(0, HEALTH_BAR_HEIGHT));
            ui.setObject("#CooldownBar" + i + ".Anchor", buildBarAnchor(0, COOLDOWN_BAR_HEIGHT));
            ui.set("#AbilitySlot" + i + ".Visible", false);
            ui.set("#AbilityTitle" + i + ".Text", "");
            ui.set("#AbilityDesc" + i + ".Text", "");
        }

        int count = Math.min(this.slots.size(), MAX_HERO_SLOTS);
        for (int i = 0; i < count; i++) {
            applyHeroSlot(ui, i, this.slots.get(i), dt);
        }
    }

    private void applyHeroSlot(@Nonnull UICommandBuilder ui, int index, @Nonnull HeroSlot heroSlot, float dt) {
        int slotNum = index + 1;
        ui.set("#HeroSlot" + slotNum + ".Visible", true);
        ui.set("#HeroBackDead" + slotNum + ".Visible", heroSlot.dead());
        ui.set("#HeroBackActive" + slotNum + ".Visible", !heroSlot.dead() && heroSlot.active() && !heroSlot.occupiedByOtherPlayer());
        ui.set("#HeroBackAvailable" + slotNum + ".Visible", !heroSlot.dead() && !heroSlot.active() && !heroSlot.occupiedByOtherPlayer());
        ui.set("#HeroBackOccupied" + slotNum + ".Visible", heroSlot.occupiedByOtherPlayer());
        ui.set("#HeroAbilityGlow" + slotNum + ".Visible", heroSlot.abilityActive() && !heroSlot.occupiedByOtherPlayer());
        ui.set("#HeroOverlay" + slotNum + ".Visible", heroSlot.occupiedByOtherPlayer());
        ui.set("#HeroIcon" + slotNum + ".Slots", new ItemGridSlot[]{heroSlot.dead() ? buildDeadPortraitSlot(heroSlot) : buildIconSlot(heroSlot)});

        // Interpolate Health
        float targetHealthPercent = heroSlot.maxHealth() > 0f ? heroSlot.health() / heroSlot.maxHealth() : 0f; 
        targetHealthPercent = Math.max(0f, Math.min(1f, targetHealthPercent));
        visualHealthPercents[index] = smoothPercent(visualHealthPercents[index], targetHealthPercent, dt, HEALTH_LERP_RATE);
        ui.setObject(
            "#HealthBar" + slotNum + ".Anchor",
            buildBarAnchor(Math.round(visualHealthPercents[index] * HEALTH_BAR_WIDTH), HEALTH_BAR_HEIGHT)
        );

        // Interpolate Cooldown
        float targetCooldownPercent = Math.max(0f, Math.min(1f, heroSlot.cooldownProgress()));
        visualCooldownPercents[index] = smoothPercent(visualCooldownPercents[index], targetCooldownPercent, dt, COOLDOWN_LERP_RATE);

        ui.set("#CooldownBack" + slotNum + ".Visible", heroSlot.showCooldown());
        ui.setObject(
            "#CooldownBar" + slotNum + ".Anchor",
            buildBarAnchor(Math.round(visualCooldownPercents[index] * COOLDOWN_BAR_WIDTH), COOLDOWN_BAR_HEIGHT)
        );

        applyAbilitySlot(ui, slotNum, heroSlot);
    }

    private void applyAbilitySlot(@Nonnull UICommandBuilder ui, int slotNum, @Nonnull HeroSlot heroSlot) {
        ui.set("#AbilitySlot" + slotNum + ".Visible", true);
        ui.set("#AbilityIcon" + slotNum + ".Slots", new ItemGridSlot[]{buildIconSlot(heroSlot)});

        AbilitySummary summary = AbilitySummary.from(heroSlot);
        ui.set("#AbilityTitle" + slotNum + ".Text", summary.title());
        ui.set("#AbilityDesc" + slotNum + ".Text", summary.description());
    }

    @Nonnull
    private static ItemGridSlot buildDeadPortraitSlot(@Nonnull HeroSlot heroSlot) {
        ItemGridSlot slot = new ItemGridSlot();
        slot.setBackground(Value.of(new PatchStyle().setTexturePath(Value.of("Pages/images/" + capitalize(heroSlot.heroId()) + "(Dead).png"))));
        slot.setName(sanitize(heroSlot.name()));
        if (heroSlot.occupiedByOtherPlayer()) {
            slot.setItemIncompatible(true);
        }
        slot.setSkipItemQualityBackground(true);
        return slot;
    }

    @Nonnull
    private static ItemGridSlot buildIconSlot(@Nonnull HeroSlot heroSlot) {
        ItemGridSlot slot = new ItemGridSlot(new ItemStack(heroSlot.itemId(), 1));
        slot.setName(sanitize(heroSlot.name()));
        slot.setIcon(Value.of(new PatchStyle().setTexturePath(Value.of("Pages/images/" + capitalize(heroSlot.heroId()) + ".png"))));
        if (heroSlot.occupiedByOtherPlayer()) {
            slot.setItemIncompatible(true);
        }
        slot.setSkipItemQualityBackground(true);
        if (heroSlot.active()) {
            slot.setBackground(Value.of(new PatchStyle().setTexturePath(Value.of("Pages/images/HeroSlotSelected.png"))));
        } else {
            slot.setBackground(Value.of(new PatchStyle().setTexturePath(Value.of("Pages/images/HeroSlot.png"))));
        }
        return slot;
    }

    @Nonnull
    private static String capitalize(@Nonnull String s) {
        if (s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    @Nonnull
    private static String sanitize(@Nonnull String text) {
        return text.replace("\"", "'");
    }

    private static float smoothPercent(float current, float target, float dt, float rate) {
        if (Math.abs(target - current) < 0.005f) {
            return target;
        }
        float alpha = 1.0f - (float)Math.exp(-rate * Math.max(dt, 0.0f));
        return current + (target - current) * alpha;
    }

    @Nonnull
    private static Anchor buildBarAnchor(int width, int height) {
        Anchor anchor = new Anchor();
        anchor.setWidth(Value.of(Math.max(0, width)));
        anchor.setHeight(Value.of(height));
        return anchor;
    }

    private record AbilitySummary(@Nonnull String title, @Nonnull String description) {
        @Nonnull
        private static AbilitySummary from(@Nonnull HeroSlot heroSlot) {
            if (heroSlot.abilitySummary() == null || heroSlot.abilitySummary().isBlank()) {
                return new AbilitySummary(heroSlot.name(), "No ability data.");
            }

            String[] parts = heroSlot.abilitySummary().split(":", 2);
            if (parts.length < 2) {
                return new AbilitySummary(heroSlot.name(), heroSlot.abilitySummary().trim());
            }

            return new AbilitySummary(
                heroSlot.name() + " - " + parts[0].trim(),
                parts[1].trim()
            );
        }
    }
}
