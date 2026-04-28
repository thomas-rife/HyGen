package com.example.exampleplugin;

import com.example.exampleplugin.commands.SummonNpcCommand;
import com.example.exampleplugin.commands.SummonNpcTripleCommand;
import com.example.exampleplugin.commands.SwapCommand;
import com.example.exampleplugin.commands.ClearNpcCommand;
import com.example.exampleplugin.commands.CompanionCombatCommand;
import com.example.exampleplugin.commands.EndGameCommand;
import com.example.exampleplugin.commands.FinishRunCommand;
import com.example.exampleplugin.commands.LevelSelectCommand;
import com.example.exampleplugin.commands.StartLevelCommand;
import com.example.exampleplugin.commands.LevelConfigCommand;
import com.example.exampleplugin.features.OptionalFeatureInstaller;
import com.example.exampleplugin.levels.LevelConfigStore;
import com.example.exampleplugin.levels.LevelEditorHudSystem;
import com.example.exampleplugin.levels.LevelRunDirectorSystem;
import com.example.exampleplugin.levels.LevelSessionManager;
import com.example.exampleplugin.levels.RunPartyHudSystem;
import com.example.exampleplugin.levels.RunDeathHandlerSystem;
import com.example.exampleplugin.npc.CompanionCombatToggleSystem;
import com.example.exampleplugin.npc.CompanionPlayerDamageFilterSystem;
import com.example.exampleplugin.npc.CompanionAttitudeRefreshSystem;
import com.example.exampleplugin.npc.CombatRetargetOnHitSystem;
import com.example.exampleplugin.npc.CombatTargetAssignmentSystem;
import com.example.exampleplugin.npc.BattleheartCameraService;
import com.example.exampleplugin.npc.FocusTargetHighlightSystem;
import com.example.exampleplugin.npc.HealerLogicSystem;
import com.example.exampleplugin.npc.VanguardGuardDamageFilterSystem;
import com.example.exampleplugin.npc.ArcherPoisonHitSystem;
import com.example.exampleplugin.npc.AbilityActivationVisualSystem;
import com.example.exampleplugin.npc.AbilityRegistry;
import com.example.exampleplugin.npc.ArcherPoisonAbility;
import com.example.exampleplugin.npc.BarbarianRageAbility;
import com.example.exampleplugin.npc.BarbarianRageDamageSystem;
import com.example.exampleplugin.npc.MonkBurstHealAbility;
import com.example.exampleplugin.npc.MageArcaneBurstAbility;
import com.example.exampleplugin.npc.OracleInvulnerabilityAbility;
import com.example.exampleplugin.npc.OracleInvulnerabilityDamageFilterSystem;
import com.example.exampleplugin.npc.VanguardGuardAbility;
import com.example.exampleplugin.npc.WardenHealingAuraAbility;
import com.example.exampleplugin.npc.WizardSlowAbility;
import com.example.exampleplugin.levels.ui.MainMenuPage;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.server.core.command.system.CommandManager;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.permissions.PermissionsModule;
import com.example.exampleplugin.npc.HotbarRoleSwapPacketFilter;
import com.hypixel.hytale.server.core.io.adapter.PacketAdapters;
import com.hypixel.hytale.server.core.io.adapter.PacketFilter;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.function.Consumer;

public class LevelingPlugin extends JavaPlugin {
    private static final String MENU_WORLD_NAME = "default";

    private static final double MENU_SPAWN_X = 773.5;
    private static final double MENU_SPAWN_Y = 63.5;
    private static final double MENU_SPAWN_Z = 2131.5;
    private static final float MENU_SPAWN_YAW = 0f;
    private static final float MENU_SPAWN_PITCH = 174.3f;
    private static final float MENU_SPAWN_ROLL = 0.0f;
    private PacketFilter hotbarRoleSwapFilter;

    public LevelingPlugin(@Nonnull JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        LevelConfigStore.get().ensureLoaded();
        OptionalFeatureInstaller.installIfPresent(this, "com.example.exampleplugin.worldgen.AiPrefabFeature");
        OptionalFeatureInstaller.installIfPresent(this, "com.example.exampleplugin.terrain.AiTerrainFeature");
        getEventRegistry().registerGlobal(PlayerReadyEvent.class, (Consumer<PlayerReadyEvent>) this::onPlayerReady);
        this.getCommandRegistry().registerCommand(new SummonNpcCommand());
        this.getCommandRegistry().registerCommand(new SummonNpcTripleCommand());
        this.getCommandRegistry().registerCommand(new SwapCommand());
        this.getCommandRegistry().registerCommand(new ClearNpcCommand());
        this.getCommandRegistry().registerCommand(new CompanionCombatCommand());
        this.getCommandRegistry().registerCommand(new LevelSelectCommand());
        this.getCommandRegistry().registerCommand(new StartLevelCommand());
        this.getCommandRegistry().registerCommand(new EndGameCommand());
        this.getCommandRegistry().registerCommand(new FinishRunCommand());
        this.getCommandRegistry().registerCommand(new LevelConfigCommand());
        this.hotbarRoleSwapFilter = PacketAdapters.registerInbound(new HotbarRoleSwapPacketFilter());
        this.getEntityStoreRegistry().registerSystem(new CompanionPlayerDamageFilterSystem());
        this.getEntityStoreRegistry().registerSystem(new VanguardGuardDamageFilterSystem());
        this.getEntityStoreRegistry().registerSystem(new CombatRetargetOnHitSystem());
        this.getEntityStoreRegistry().registerSystem(new CompanionAttitudeRefreshSystem());
        this.getEntityStoreRegistry().registerSystem(new CombatTargetAssignmentSystem());
        this.getEntityStoreRegistry().registerSystem(new CompanionCombatToggleSystem());
        this.getEntityStoreRegistry().registerSystem(new FocusTargetHighlightSystem());
        this.getEntityStoreRegistry().registerSystem(new AbilityActivationVisualSystem());
        this.getEntityStoreRegistry().registerSystem(new ArcherPoisonHitSystem());
        this.getEntityStoreRegistry().registerSystem(new BarbarianRageDamageSystem());
        this.getEntityStoreRegistry().registerSystem(new HealerLogicSystem());
        this.getEntityStoreRegistry().registerSystem(new OracleInvulnerabilityDamageFilterSystem());
        this.getEntityStoreRegistry().registerSystem(new RunDeathHandlerSystem());
        this.getEntityStoreRegistry().registerSystem(new LevelRunDirectorSystem());
        this.getEntityStoreRegistry().registerSystem(new RunPartyHudSystem());
        this.getEntityStoreRegistry().registerSystem(new LevelEditorHudSystem());

        AbilityRegistry.register(new ArcherPoisonAbility());
        AbilityRegistry.register(new BarbarianRageAbility());
        AbilityRegistry.register(new MageArcaneBurstAbility());
        AbilityRegistry.register(new MonkBurstHealAbility());
        AbilityRegistry.register(new OracleInvulnerabilityAbility());
        AbilityRegistry.register(new VanguardGuardAbility());
        AbilityRegistry.register(new WardenHealingAuraAbility());
        AbilityRegistry.register(new WizardSlowAbility());
    }

    @Override
    protected void start() {
    }

    private void onPlayerReady(PlayerReadyEvent event) {
        Ref<EntityStore> ref = event.getPlayerRef();
        if (ref != null && ref.isValid()) {
            Store<EntityStore> store = ref.getStore();
            Player player = event.getPlayer();
            PlayerRef playerRefComponent = store.getComponent(ref, PlayerRef.getComponentType());
            if (player == null || playerRefComponent == null) {
                return;
            }

            Universe universe = Universe.get();
            PermissionsModule.get().addUserToGroup(playerRefComponent.getUuid(), "OP");
            
            World currentWorld = player.getWorld();
            if (currentWorld != null
                && ((LevelSessionManager.get().isRunParticipant(playerRefComponent.getUuid())
                && LevelSessionManager.get().isActiveRunWorld(currentWorld.getWorldConfig().getUuid()))
                || currentWorld.getName().startsWith(com.example.exampleplugin.terrain.AiTerrainSettings.DEFAULT_GENERATED_WORLD_PREFIX))) {
                return;
            }
            World menuWorld = universe.getWorld(MENU_WORLD_NAME);
            if (menuWorld == null) {
                menuWorld = universe.getDefaultWorld();
            }
            if (menuWorld == null) {
                return;
            }

            if (currentWorld != menuWorld) {
                World fromWorld = currentWorld == null ? menuWorld : currentWorld;
                playerRefComponent.removeFromStore();
                menuWorld.addPlayer(playerRefComponent, menuSpawnTransform(), Boolean.TRUE, Boolean.FALSE).whenComplete((ignored, throwable) -> {
                    if (throwable != null) {
                        fromWorld.addPlayer(playerRefComponent, null, Boolean.TRUE, Boolean.FALSE);
                        return;
                    }
                    openMainMenu(playerRefComponent);
                });
                return;
            }

            teleportToMenuSpawn(store, ref);
            openMainMenu(playerRefComponent);
        }
    }

    private static void teleportToMenuSpawn(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        Vector3f rotation = new Vector3f(MENU_SPAWN_YAW, MENU_SPAWN_PITCH, MENU_SPAWN_ROLL);
        store.putComponent(
            ref,
            Teleport.getComponentType(),
            Teleport.createExact(
                new Vector3d(MENU_SPAWN_X, MENU_SPAWN_Y, MENU_SPAWN_Z),
                rotation,
                rotation
            )
        );
    }

    @Nonnull
    private static Transform menuSpawnTransform() {
        return new Transform(MENU_SPAWN_X, MENU_SPAWN_Y, MENU_SPAWN_Z, MENU_SPAWN_YAW, MENU_SPAWN_PITCH, MENU_SPAWN_ROLL);
    }

    private static void openMainMenu(@Nonnull PlayerRef playerRef) {
        Ref<EntityStore> ref = playerRef.getReference();
        if (ref == null || !ref.isValid()) {
            return;
        }
        Store<EntityStore> store = ref.getStore();
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            return;
        }
        player.getPageManager().openCustomPage(ref, store, new MainMenuPage(playerRef));

        // Apply environment and player state via commands
        CommandManager cm = CommandManager.get();
        cm.handleCommand(playerRef, "hud hide ultimate");
        cm.handleCommand(playerRef, "time set 5");
        cm.handleCommand(playerRef, "time pause");
        cm.handleCommand(playerRef, "gamemode creative");
        cm.handleCommand(playerRef, "fly false");
        cm.handleCommand(playerRef, "effect give @s night_vision 999999 1 true");
    }

    @Override
    protected void shutdown() {
        OptionalFeatureInstaller.shutdownIfPresent("com.example.exampleplugin.terrain.AiTerrainFeature");
        if (this.hotbarRoleSwapFilter != null) {
            PacketAdapters.deregisterInbound(this.hotbarRoleSwapFilter);
            this.hotbarRoleSwapFilter = null;
        }
        super.shutdown();
    }
}
