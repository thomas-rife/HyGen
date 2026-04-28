package com.example.exampleplugin.levels.ui;

import com.example.exampleplugin.levels.HeroSelectionStore;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.ui.ItemGridSlot;
import com.hypixel.hytale.server.core.ui.PatchStyle;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.List;

public class HeroSelectPage extends InteractiveCustomUIPage<HeroSelectPage.Data> {
    private static final String SELECTED_DROP = "selected_hero_drop";
    private static final String AVAILABLE_DROP = "available_hero_drop";

    public static class Data {
        public String action;
        public Integer slotIndex;
        public Integer sourceSlotId;
        public String itemStackId;
        public static final BuilderCodec<Data> CODEC = BuilderCodec.builder(Data.class, Data::new)
            .addField(new KeyedCodec<>("Action", Codec.STRING), (d, v) -> d.action = v, d -> d.action)
            .addField(new KeyedCodec<>("SlotIndex", Codec.INTEGER), (d, v) -> d.slotIndex = v, d -> d.slotIndex)
            .addField(new KeyedCodec<>("SourceSlotId", Codec.INTEGER), (d, v) -> d.sourceSlotId = v, d -> d.sourceSlotId)
            .addField(new KeyedCodec<>("ItemStackId", Codec.STRING), (d, v) -> d.itemStackId = v, d -> d.itemStackId)
            .build();
    }

    public HeroSelectPage(@Nonnull PlayerRef playerRef) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, Data.CODEC);
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder ui, @Nonnull UIEventBuilder events, @Nonnull Store<EntityStore> store) {
        ui.append("Pages/HeroSelectPage.ui");
        writeHeroes(ui);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#BackBtn", EventData.of("Action", "back"), false);
        events.addEventBinding(CustomUIEventBindingType.Dropped, "#SelectedHeroGrid", EventData.of("Action", SELECTED_DROP), false);
        events.addEventBinding(CustomUIEventBindingType.Dropped, "#AvailableHeroGrid", EventData.of("Action", AVAILABLE_DROP), false);
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull Data data) {
        String action = data.action == null ? "" : data.action.trim().toLowerCase();
        if ("back".equals(action)) {
            Player player = store.getComponent(ref, Player.getComponentType());
            if (player != null) {
                player.getPageManager().openCustomPage(ref, store, new MainMenuPage(this.playerRef));
            }
            return;
        }
        if (SELECTED_DROP.equals(action)) {
            HeroSelectionStore.dropHero(this.playerRef.getUuid(), HeroSelectionStore.TargetGrid.SELECTED, safeIndex(data), data.itemStackId);
            refreshHeroes();
        } else if (AVAILABLE_DROP.equals(action)) {
            HeroSelectionStore.dropHero(this.playerRef.getUuid(), HeroSelectionStore.TargetGrid.AVAILABLE, safeIndex(data), data.itemStackId);
            refreshHeroes();
        }
    }

    private static int safeIndex(@Nonnull Data data) {
        return data.slotIndex == null ? -1 : data.slotIndex;
    }

    private void refreshHeroes() {
        UICommandBuilder ui = new UICommandBuilder();
        writeHeroes(ui);
        sendUpdate(ui, null, false);
    }

    private void writeHeroes(@Nonnull UICommandBuilder ui) {
        List<HeroSelectionStore.Hero> selectedHeroes = HeroSelectionStore.getSelectedHeroes(this.playerRef.getUuid());
        List<HeroSelectionStore.Hero> availableHeroes = HeroSelectionStore.getAvailableHeroes(this.playerRef.getUuid());

        ui.set("#SelectedHeroGrid.Slots", buildSlots(selectedHeroes));
        ui.set("#AvailableHeroGrid.Slots", buildSlots(availableHeroes));
    }

    @Nonnull
    private static ItemGridSlot[] buildSlots(@Nonnull List<HeroSelectionStore.Hero> heroes) {
        ItemGridSlot[] slots = new ItemGridSlot[heroes.size()];
        for (int i = 0; i < heroes.size(); i++) {
            HeroSelectionStore.Hero hero = heroes.get(i);
            if (hero != null) {
                slots[i] = buildSlot(hero);
            } else {
                slots[i] = new ItemGridSlot();
            }
        }
        return slots;
    }

    @Nonnull
    private static ItemGridSlot buildSlot(@Nonnull HeroSelectionStore.Hero hero) {
        ItemGridSlot slot = new ItemGridSlot(new ItemStack(hero.itemId(), 1));
        slot.setName(hero.displayName());
        slot.setDescription("[" + hero.heroClass().name() + "] " + hero.description());
        slot.setIcon(Value.of(new PatchStyle().setTexturePath(Value.of("Pages/images/" + capitalize(hero.id()) + ".png"))));
        slot.setSkipItemQualityBackground(true);
        slot.setActivatable(true);
        return slot;
    }

    @Nonnull
    private static String capitalize(@Nonnull String s) {
        if (s.isEmpty()) {
            return s;
        }
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
