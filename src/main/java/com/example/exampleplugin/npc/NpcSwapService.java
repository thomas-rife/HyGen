package com.example.exampleplugin.npc;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.protocol.PlayerSkin;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.entity.Frozen;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerSkinComponent;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.logging.Level;

public final class NpcSwapService {
    private static final DisabledLogger LOGGER = new DisabledLogger();
    private static final short PLAYER_WEAPON_SLOT = 3;
    private static final short NPC_WEAPON_SLOT = 0;

    private NpcSwapService() {
    }

    @Nullable
    public static String swapWithNpc(
        @Nonnull ComponentAccessor<EntityStore> accessor,
        @Nonnull Ref<EntityStore> playerRef,
        @Nonnull Ref<EntityStore> npcRef
    ) {
        LOGGER.at(Level.INFO).log("swapWithNpc start player=%s npc=%s", playerRef, npcRef);
        if (!playerRef.isValid()) {
            LOGGER.at(Level.WARNING).log("swapWithNpc abort: invalid player=%s", playerRef);
            return "Player entity is no longer valid.";
        }
        if (!npcRef.isValid()) {
            LOGGER.at(Level.WARNING).log("swapWithNpc abort: invalid npc=%s", npcRef);
            return "Target NPC is no longer valid.";
        }
        EntityStore entityStore = accessor.getExternalData();
        if (npcRef.getStore().getExternalData() != entityStore || playerRef.getStore().getExternalData() != entityStore) {
            LOGGER.at(Level.WARNING).log("swapWithNpc abort: different stores player=%s npc=%s", playerRef, npcRef);
            return "Player/NPC are not in the same world.";
        }

        ModelComponent playerModelComponent = accessor.getComponent(playerRef, ModelComponent.getComponentType());
        ModelComponent npcModelComponent = accessor.getComponent(npcRef, ModelComponent.getComponentType());
        PlayerSkinComponent playerSkinComponent = accessor.getComponent(playerRef, PlayerSkinComponent.getComponentType());
        PlayerSkinComponent npcSkinComponent = accessor.getComponent(npcRef, PlayerSkinComponent.getComponentType());
        TransformComponent playerTransform = accessor.getComponent(playerRef, TransformComponent.getComponentType());
        TransformComponent npcTransform = accessor.getComponent(npcRef, TransformComponent.getComponentType());
        HeadRotation playerHeadRotation = accessor.getComponent(playerRef, HeadRotation.getComponentType());
        HeadRotation npcHeadRotation = accessor.getComponent(npcRef, HeadRotation.getComponentType());
        NPCEntity targetNpc = accessor.getComponent(npcRef, NPCEntity.getComponentType());
        Player player = accessor.getComponent(playerRef, Player.getComponentType());
        PlayerRef playerRefComponent = accessor.getComponent(playerRef, PlayerRef.getComponentType());
        EntityStatMap playerStats = accessor.getComponent(playerRef, EntityStatMap.getComponentType());
        EntityStatMap npcStats = accessor.getComponent(npcRef, EntityStatMap.getComponentType());
        LOGGER.at(Level.INFO).log(
            "swapWithNpc components player=%s npc=%s playerModel=%s npcModel=%s playerSkin=%s npcSkin=%s playerTransform=%s npcTransform=%s player=%s targetNpc=%s playerInv=%s npcInv=%s",
            playerRef,
            npcRef,
            playerModelComponent != null && playerModelComponent.getModel() != null,
            npcModelComponent != null && npcModelComponent.getModel() != null,
            playerSkinComponent != null,
            npcSkinComponent != null,
            playerTransform != null,
            npcTransform != null,
            player != null,
            targetNpc != null,
            player != null && player.getInventory() != null,
            targetNpc != null && targetNpc.getInventory() != null
        );

        if (targetNpc == null) {
            LOGGER.at(Level.WARNING).log("swapWithNpc abort: target is not NPC npc=%s", npcRef);
            return "Tracked entity is not an NPC.";
        }
        if (player == null) {
            LOGGER.at(Level.WARNING).log("swapWithNpc abort: missing Player component player=%s", playerRef);
            return "Player component missing.";
        }
        Inventory playerInventory = player.getInventory();
        Inventory npcInventory = targetNpc.getInventory();
        if (playerInventory == null || npcInventory == null) {
            LOGGER.at(Level.WARNING).log(
                "swapWithNpc abort: missing inventory playerInv=%s npcInv=%s",
                playerInventory != null,
                npcInventory != null
            );
            return "Missing inventory on player or NPC.";
        }
        if (npcModelComponent == null || npcModelComponent.getModel() == null) {
            LOGGER.at(Level.WARNING).log("swapWithNpc abort: missing NPC model npc=%s", npcRef);
            return "Missing model component on NPC.";
        }
        if (playerTransform == null || npcTransform == null) {
            LOGGER.at(Level.WARNING).log(
                "swapWithNpc abort: missing transform playerTransform=%s npcTransform=%s",
                playerTransform != null,
                npcTransform != null
            );
            return "Missing transform component on player or NPC.";
        }

        ItemStack oldPlayerWeapon = getHotbarItem(playerInventory, PLAYER_WEAPON_SLOT);
        ItemStack targetNpcWeapon = getActiveHotbarItem(npcInventory);

        Model oldPlayerModel = playerModelComponent == null ? null : playerModelComponent.getModel();
        Model targetModel = npcModelComponent.getModel();
        PlayerSkin oldPlayerSkin = playerSkinComponent == null ? null : playerSkinComponent.getPlayerSkin();
        PlayerSkin targetSkin = npcSkinComponent == null ? null : npcSkinComponent.getPlayerSkin();
        LOGGER.at(Level.INFO).log(
            "swapWithNpc model oldPlayerModel=%s targetModel=%s oldPlayerSkin=%s targetSkin=%s playerWeapon=%s npcWeapon=%s",
            oldPlayerModel == null ? null : oldPlayerModel.getModelAssetId(),
            targetModel.getModelAssetId(),
            oldPlayerSkin != null,
            targetSkin != null,
            oldPlayerWeapon != null,
            targetNpcWeapon != null
        );

        Vector3d oldPlayerPos = new Vector3d(playerTransform.getPosition());
        Vector3d targetPos = new Vector3d(npcTransform.getPosition());
        Vector3f oldPlayerRot = new Vector3f(playerTransform.getRotation());
        Vector3f targetRot = new Vector3f(npcTransform.getRotation());
        Vector3f oldPlayerHead = playerHeadRotation != null ? new Vector3f(playerHeadRotation.getRotation()) : new Vector3f(oldPlayerRot);
        Vector3f targetHead = npcHeadRotation != null ? new Vector3f(npcHeadRotation.getRotation()) : new Vector3f(targetRot);
        boolean overheadCamera = playerRefComponent == null
            || !BattleheartCameraPreferences.isThirdPersonCameraEnabled(playerRefComponent.getUuid());
        Vector3d playerDestinationPos = overheadCamera
            ? new Vector3d(targetPos.x, oldPlayerPos.y, targetPos.z)
            : targetPos;

        putOrRemoveSkin(accessor, playerRef, targetSkin);
        accessor.putComponent(playerRef, ModelComponent.getComponentType(), new ModelComponent(targetModel));
        accessor.putComponent(playerRef, Teleport.getComponentType(), Teleport.createExact(playerDestinationPos, oldPlayerRot, oldPlayerHead));
        LOGGER.at(Level.INFO).log(
            "swapWithNpc player teleported to x=%s y=%s z=%s overheadCamera=%s",
            playerDestinationPos.x,
            playerDestinationPos.y,
            playerDestinationPos.z,
            overheadCamera
        );
        if (playerHeadRotation != null) {
            playerHeadRotation.getRotation().assign(oldPlayerHead);
        }

        putOrRemoveSkin(accessor, npcRef, oldPlayerSkin);
        if (oldPlayerModel != null) {
            accessor.putComponent(npcRef, ModelComponent.getComponentType(), new ModelComponent(oldPlayerModel));
        }
        accessor.putComponent(npcRef, Teleport.getComponentType(), Teleport.createExact(oldPlayerPos, oldPlayerRot, oldPlayerHead));
        LOGGER.at(Level.INFO).log(
            "swapWithNpc npc teleported to x=%s y=%s z=%s",
            oldPlayerPos.x,
            oldPlayerPos.y,
            oldPlayerPos.z
        );
        if (npcHeadRotation != null) {
            npcHeadRotation.getRotation().assign(oldPlayerHead);
        }

        swapInventoryContainers(playerInventory, npcInventory);
        swapRoleWeapons(playerInventory, npcInventory, oldPlayerWeapon, targetNpcWeapon);

        if (accessor.getComponent(npcRef, Frozen.getComponentType()) != null) {
            accessor.removeComponent(npcRef, Frozen.getComponentType());
            LOGGER.at(Level.INFO).log("swapWithNpc removed Frozen from npc=%s", npcRef);
        }

        int healthIndex = DefaultEntityStatTypes.getHealth();
        if (healthIndex >= 0 && playerStats != null && npcStats != null) {
            EntityStatValue playerHealth = playerStats.get(healthIndex);
            EntityStatValue npcHealth = npcStats.get(healthIndex);
            if (playerHealth != null && npcHealth != null) {
                float playerHealthPercent = healthPercent(playerHealth);
                float npcHealthPercent = healthPercent(npcHealth);
                playerStats.setStatValue(healthIndex, npcHealthPercent * playerHealth.getMax());
                npcStats.setStatValue(healthIndex, playerHealthPercent * npcHealth.getMax());
                LOGGER.at(Level.INFO).log(
                    "swapWithNpc swapped health playerPercent=%s npcPercent=%s",
                    playerHealthPercent,
                    npcHealthPercent
                );
            }
        }

        LOGGER.at(Level.INFO).log("swapWithNpc complete player=%s npc=%s", playerRef, npcRef);
        return null;
    }

    private static void putOrRemoveSkin(
        @Nonnull ComponentAccessor<EntityStore> accessor,
        @Nonnull Ref<EntityStore> ref,
        @Nullable PlayerSkin skin
    ) {
        if (skin == null) {
            if (accessor.getComponent(ref, PlayerSkinComponent.getComponentType()) != null) {
                accessor.removeComponent(ref, PlayerSkinComponent.getComponentType());
            }
            return;
        }
        accessor.putComponent(ref, PlayerSkinComponent.getComponentType(), new PlayerSkinComponent(skin));
    }

    private static void swapInventoryContainers(@Nonnull Inventory playerInventory, @Nonnull Inventory npcInventory) {
        swapContainers(playerInventory.getStorage(), npcInventory.getStorage());
        swapContainers(playerInventory.getArmor(), npcInventory.getArmor());
        swapContainers(playerInventory.getBackpack(), npcInventory.getBackpack());
    }

    private static void swapRoleWeapons(
        @Nonnull Inventory playerInventory,
        @Nonnull Inventory npcInventory,
        @Nullable ItemStack oldPlayerWeapon,
        @Nullable ItemStack targetNpcWeapon
    ) {
        ItemContainer playerHotbar = playerInventory.getHotbar();
        ItemContainer npcHotbar = npcInventory.getHotbar();
        if (playerHotbar == null || npcHotbar == null) {
            return;
        }

        playerHotbar.clear();
        npcHotbar.clear();
        clearContainer(playerInventory.getTools());
        clearContainer(playerInventory.getUtility());
        clearContainer(npcInventory.getTools());
        clearContainer(npcInventory.getUtility());

        if (targetNpcWeapon != null && PLAYER_WEAPON_SLOT < playerHotbar.getCapacity()) {
            playerHotbar.setItemStackForSlot(PLAYER_WEAPON_SLOT, targetNpcWeapon);
        }
        if (oldPlayerWeapon != null && npcHotbar.getCapacity() > 0) {
            short npcSlot = NPC_WEAPON_SLOT < npcHotbar.getCapacity() ? NPC_WEAPON_SLOT : 0;
            npcHotbar.setItemStackForSlot(npcSlot, oldPlayerWeapon);
        }
    }

    @Nullable
    private static ItemStack getHotbarItem(@Nonnull Inventory inventory, short slot) {
        ItemContainer hotbar = inventory.getHotbar();
        if (hotbar == null || slot < 0 || slot >= hotbar.getCapacity()) {
            return null;
        }
        return hotbar.getItemStack(slot);
    }

    @Nullable
    private static ItemStack getActiveHotbarItem(@Nonnull Inventory inventory) {
        byte slot = inventory.getActiveHotbarSlot();
        if (slot < 0) {
            slot = NPC_WEAPON_SLOT;
        }
        return getHotbarItem(inventory, slot);
    }

    private static void clearContainer(@Nullable ItemContainer container) {
        if (container != null) {
            container.clear();
        }
    }

    private static float healthPercent(@Nonnull EntityStatValue health) {
        float max = health.getMax();
        if (max <= 0f) {
            return 0f;
        }
        float percent = health.get() / max;
        return Math.max(0f, Math.min(1f, percent));
    }

    private static void swapContainers(@Nullable ItemContainer container1, @Nullable ItemContainer container2) {
        if (container1 == null || container2 == null) {
            return;
        }

        // Get all items from both containers
        java.util.List<ItemStack> items1 = new java.util.ArrayList<>();
        java.util.List<ItemStack> items2 = new java.util.ArrayList<>();

        short capacity1 = container1.getCapacity();
        short capacity2 = container2.getCapacity();

        for (short i = 0; i < capacity1; i++) {
            ItemStack item = container1.getItemStack(i);
            items1.add(item);
        }

        for (short i = 0; i < capacity2; i++) {
            ItemStack item = container2.getItemStack(i);
            items2.add(item);
        }

        // Clear both containers
        container1.clear();
        container2.clear();

        // Put items back swapped
        for (short i = 0; i < items2.size(); i++) {
            ItemStack item = items2.get(i);
            if (item != null) {
                container1.setItemStackForSlot(i, item);
            }
        }

        for (short i = 0; i < items1.size(); i++) {
            ItemStack item = items1.get(i);
            if (item != null) {
                container2.setItemStackForSlot(i, item);
            }
        }
    }

    private static final class DisabledLogger {
        private static final Api API = new Api();

        private Api at(Level level) {
            return API;
        }
    }

    private static final class Api {
        private void log(String message, Object... args) {
        }
    }
}
