package com.example.exampleplugin.levels.ui;

import com.example.exampleplugin.terrain.AiTerrainConfig;
import com.example.exampleplugin.terrain.AiTerrainPlacementMode;
import com.example.exampleplugin.terrain.AiTerrainService;
import com.example.exampleplugin.terrain.GenerationJob;
import com.example.exampleplugin.terrain.GenerationJobState;
import com.example.exampleplugin.terrain.SafeSpawnFinder;
import com.example.exampleplugin.terrain.TerrainPackage;
import com.example.exampleplugin.terrain.TerrainPackageDescriptor;
import com.example.exampleplugin.terrain.TerrainRequestPlan;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import com.example.exampleplugin.levels.LevelConfigStore;
import com.example.exampleplugin.levels.LevelSessionManager;
import com.example.exampleplugin.levels.model.LevelDefinition;
import com.hypixel.hytale.server.core.Message;
import java.util.Comparator;
import java.util.List;

public class AiWorldGenPage extends InteractiveCustomUIPage<AiWorldGenPage.Data> {
    private static final String DEFAULT_STRENGTH_TEXT = "0.4";
    private static final Map<UUID, Boolean> isAiModeEnabled = new ConcurrentHashMap<>();
    private static final Map<UUID, Boolean> isExploreModeEnabled = new ConcurrentHashMap<>();
    private static final Deque<String> outputLines = new ArrayDeque<>();
    private static final Map<UUID, String> draftInputs = new ConcurrentHashMap<>();
    private static final Map<UUID, String> draftStrengths = new ConcurrentHashMap<>();
    private static final Map<UUID, String> statusByPlayer = new ConcurrentHashMap<>();
    private static final Map<UUID, AiWorldGenPage> activePages = new ConcurrentHashMap<>();
    private static volatile boolean isGenerating = false;

    public static class Data {
        public String action;
        public String inputField;
        public String strengthField;
        public static final BuilderCodec<Data> CODEC = BuilderCodec.builder(Data.class, Data::new)
            .addField(new KeyedCodec<>("Action", Codec.STRING), (d, v) -> d.action = v, d -> d.action)
            .addField(new KeyedCodec<>("@InputField", Codec.STRING), (d, v) -> d.inputField = v, d -> d.inputField)
            .addField(new KeyedCodec<>("@StrengthField", Codec.STRING), (d, v) -> d.strengthField = v, d -> d.strengthField)
            .build();
    }

    public AiWorldGenPage(@Nonnull PlayerRef playerRef) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, Data.CODEC);
        if (outputLines.isEmpty()) {
            outputLines.add("Ready to generate terrain.");
        }
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder ui, @Nonnull UIEventBuilder events, @Nonnull Store<EntityStore> store) {
        activePages.put(this.playerRef.getUuid(), this);
        ui.append("Pages/AiWorldGenPage.ui");
        
        UUID playerUuid = this.playerRef.getUuid();
        boolean isAi = isAiModeEnabled.getOrDefault(playerUuid, false);
        boolean isExploreMode = isExploreModeEnabled.getOrDefault(playerUuid, false);

        ui.set("#ModeToggleLabel.Text", isAi ? "Mode: AI Generate" : "Mode: Map Select");        ui.set("#MapSelectPane.Visible", !isAi);
        ui.set("#AiGeneratePane.Visible", isAi);
        
        ui.set("#ExploreToggleLabel.Text", isExploreMode ? "Explore Mode: ON" : "Explore Mode: OFF");
        ui.set("#InputField.Value", draftInputs.getOrDefault(this.playerRef.getUuid(), ""));
        ui.set("#StrengthField.Value", draftStrengths.getOrDefault(this.playerRef.getUuid(), DEFAULT_STRENGTH_TEXT));
        writeOutput(ui);

        ui.set("#SendBtn.Visible", !isGenerating);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#CloseBtn", EventData.of("Action", "close"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ModeToggleBtn", EventData.of("Action", "toggle_mode"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ExploreToggleBtn", EventData.of("Action", "toggle_explore"), false);
        
        // Configure pre-defined map buttons
        List<LevelDefinition> levels = LevelConfigStore.get().getLevels().stream()
            .sorted(Comparator.comparingInt(AiWorldGenPage::sortOrder))
            .toList();
            
        for (int i = 0; i < 6; i++) {
            String baseSelector = "#MapLevelNode" + i;
            String labelSelector = "#MapLevelLabel" + i + ".Text";
            if (i < levels.size()) {
                LevelDefinition level = levels.get(i);
                String levelId = safe(level.levelId, "level_" + (i + 1));
                String levelName = safe(level.levelName, "Level " + (i + 1));
                
                ui.set(baseSelector + ".Visible", true);
                ui.set(labelSelector, levelName);
                
                events.addEventBinding(
                    CustomUIEventBindingType.Activating,
                    baseSelector,
                    EventData.of("Action", "start_map:" + levelId),
                    false
                );
            } else {
                ui.set(baseSelector + ".Visible", false);
            }
        }
        
        events.addEventBinding(
            CustomUIEventBindingType.ValueChanged,
            "#InputField",
            EventData.of("@InputField", "#InputField.Value"),
            false
        );
        events.addEventBinding(
            CustomUIEventBindingType.ValueChanged,
            "#StrengthField",
            EventData.of("@StrengthField", "#StrengthField.Value"),
            false
        );
        events.addEventBinding(
            CustomUIEventBindingType.Activating,
            "#SendBtn",
            EventData.of("Action", "send")
                .append("@InputField", "#InputField.Value")
                .append("@StrengthField", "#StrengthField.Value"),
            false
        );

        if (isGenerating) {
            return;
        } else {
            return;
        }
    }

    private static int sortOrder(@Nonnull LevelDefinition level) {
        return level.orderIndex == null ? Integer.MAX_VALUE : level.orderIndex;
    }
    
    @Nonnull
    private static String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull Data data) {
        if (data.inputField != null) {
            draftInputs.put(this.playerRef.getUuid(), data.inputField);
        }
        if (data.strengthField != null) {
            draftStrengths.put(this.playerRef.getUuid(), data.strengthField);
        }

        String action = data.action == null ? "" : data.action.trim().toLowerCase();

        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            return;
        }

        if ("toggle_mode".equals(action)) {
            UUID playerUuid = this.playerRef.getUuid();
            isAiModeEnabled.put(playerUuid, !isAiModeEnabled.getOrDefault(playerUuid, false));
            player.getPageManager().openCustomPage(ref, store, new AiWorldGenPage(this.playerRef));
            return;
        }

        if ("toggle_explore".equals(action)) {
            UUID playerUuid = this.playerRef.getUuid();
            isExploreModeEnabled.put(playerUuid, !isExploreModeEnabled.getOrDefault(playerUuid, false));
            player.getPageManager().openCustomPage(ref, store, new AiWorldGenPage(this.playerRef));
            return;
        }
        
        if (action.startsWith("start_map:")) {
            String levelId = data.action.substring("start_map:".length()).trim();
            LevelSessionManager.get().startEndlessLevelForPlayer(this.playerRef, levelId).whenComplete((result, throwable) -> {
                if (throwable != null) {
                    String reason = throwable.getCause() != null ? throwable.getCause().getMessage() : throwable.getMessage();
                    this.playerRef.sendMessage(Message.raw("Failed to start endless run: " + reason));
                    return;
                }
                this.playerRef.sendMessage(Message.raw("Started Endless Run on " + result.levelName() + "."));
            });
            close();
            return;
        }

        if ("close".equals(action)) {
            activePages.remove(this.playerRef.getUuid(), this);
            player.getPageManager().openCustomPage(ref, store, new MainMenuPage(this.playerRef));
        } else if ("send".equals(action)) {
            if (isGenerating) {
                setStatus(this.playerRef.getUuid(), "Terrain generation is already running.");
                player.getPageManager().openCustomPage(ref, store, new AiWorldGenPage(this.playerRef));
                return;
            }
            String prompt = data.inputField != null ? data.inputField.trim() : "";
            Float parsedStrength = parseStrength(data.strengthField);
            if (prompt.isEmpty()) {
                setStatus(this.playerRef.getUuid(), "Enter a terrain description.");
            } else if (parsedStrength == null) {
                setStatus(this.playerRef.getUuid(), "Structure guidance must be a number from 0.0 to 1.0.");
            } else {
                draftInputs.remove(this.playerRef.getUuid());
                draftStrengths.put(this.playerRef.getUuid(), formatStrength(parsedStrength));
                clearOutput();
                addOutput("Prompt: " + prompt);
                addOutput("structure guidance: " + formatStrength(parsedStrength));
                boolean isExploreMode = isExploreModeEnabled.getOrDefault(this.playerRef.getUuid(), false);
                executeTerrainGeneration(prompt, parsedStrength, player, !isExploreMode);
            }
            player.getPageManager().openCustomPage(ref, store, new AiWorldGenPage(this.playerRef));
        } else {
            sendUpdate(new UICommandBuilder(), null, false);
        }
    }

    private void executeTerrainGeneration(@Nonnull String prompt, float img2imgStrength, @Nonnull Player player, boolean endlessMode) {
        isGenerating = true;
        setStatus(this.playerRef.getUuid(), "Starting terrain generation...");

        AiTerrainService service = AiTerrainService.get();
        if (service == null) {
            setStatus(this.playerRef.getUuid(), "AI terrain service is unavailable.");
            isGenerating = false;
            return;
        }

        TransformComponent transform = player.getTransformComponent();
        if (transform == null) {
            setStatus(this.playerRef.getUuid(), "Could not resolve player position.");
            isGenerating = false;
            return;
        }

        int playerX = (int) Math.floor(transform.getPosition().getX());
        int playerZ = (int) Math.floor(transform.getPosition().getZ());
        int seed = ThreadLocalRandom.current().nextInt();
        AiTerrainConfig config = service.config();
        TerrainRequestPlan plan = service.planRequest(prompt, playerX, playerZ, seed, config, img2imgStrength);

        String localJobId = UUID.randomUUID().toString().replace("-", "");
        String worldName = service.voidWorldManager().buildWorldName(config, this.playerRef, localJobId);
        GenerationJob job = new GenerationJob(this.playerRef, prompt, worldName, service.voidWorldManager().worldPath(worldName));

        World currentWorld = service.voidWorldManager().resolvePlayerWorld(this.playerRef);
        if (currentWorld == null) {
            setStatus(this.playerRef.getUuid(), "Could not resolve the current world.");
            isGenerating = false;
            return;
        }

        addOutput("Creating void world " + worldName + "...");
        setStatus(this.playerRef.getUuid(), "Creating void world...");

        service.voidWorldManager().createVoidWorld(worldName, job.worldPath())
            .thenCompose(createdWorld -> {
                job.setGeneratedWorld(createdWorld);
                job.transition(GenerationJobState.REQUESTING_PYTHON, "Requesting terrain from AI...");
                addOutput("Requesting terrain from AI...");
                setStatus(this.playerRef.getUuid(), "Requesting terrain from AI...");
                return CompletableFuture.supplyAsync(() -> service.requestTerrainPackageDescriptor(plan.request()), service.requestExecutor());
            })
            .thenCompose(descriptor -> {
                job.setPackageDescriptor(descriptor);
                job.transition(GenerationJobState.DOWNLOADING_PACKAGE, "Downloading terrain package...");
                addOutput("Downloading terrain package...");
                setStatus(this.playerRef.getUuid(), "Downloading terrain package...");
                return CompletableFuture.supplyAsync(() -> service.loadTerrainPackage(descriptor), service.requestExecutor());
            })
            .thenCompose(terrainPackage -> {
                job.setTerrainPackage(terrainPackage);
                job.transition(GenerationJobState.PLACING_TERRAIN, "Placing terrain...");
                addOutput("Placing terrain...");
                setStatus(this.playerRef.getUuid(), "Placing terrain...");
                World generatedWorld = job.generatedWorld();
                if (generatedWorld == null) {
                    throw new IllegalStateException("Generated world was not created.");
                }
                int originX = terrainPackage.metadata().originX != 0
                    ? terrainPackage.metadata().originX
                    : plan.playerAnchorX() - (terrainPackage.width() / 2);
                int originZ = terrainPackage.metadata().originZ != 0
                    ? terrainPackage.metadata().originZ
                    : plan.playerAnchorZ() - (terrainPackage.depth() / 2);
                SafeSpawnFinder.SpawnSelection spawnSelection = service.safeSpawnFinder().findSpawn(terrainPackage, originX, originZ);
                return service.terrainPlacer().placeTerrainAsync(
                        generatedWorld,
                        terrainPackage,
                        originX,
                        originZ,
                        config.tileBatchSize,
                        AiTerrainPlacementMode.parse(config.placementMode),
                        config.surfaceShellDepth,
                        (stage, completedTiles, totalTiles, affectedCount) ->
                            setStatus(this.playerRef.getUuid(), formatProgress("Placing terrain", completedTiles, totalTiles))
                    )
                    .thenCompose(placed -> {
                        job.transition(GenerationJobState.PLACING_WATER, "Filling water...");
                        addOutput("Filling water...");
                        setStatus(this.playerRef.getUuid(), "Filling water...");
                        return service.terrainPlacer().placeWaterAsync(
                                generatedWorld,
                                terrainPackage,
                                originX,
                                originZ,
                                config.tileBatchSize,
                                (stage, completedTiles, totalTiles, affectedCount) ->
                                    setStatus(this.playerRef.getUuid(), formatProgress("Filling water", completedTiles, totalTiles))
                            )
                            .thenCompose(waterPlaced -> {
                                job.transition(GenerationJobState.PLACING_DECORATIONS, "Placing decorations...");
                                addOutput("Placing decorations...");
                                setStatus(this.playerRef.getUuid(), "Placing decorations...");
                                return service.terrainPlacer().placeDecorationsAsync(generatedWorld, terrainPackage, originX, originZ, spawnSelection)
                                    .thenCompose(decorationsPlaced -> {
                                        job.transition(GenerationJobState.FINALIZING, "Finalizing world and spawn...");
                                        addOutput("Finalizing world...");
                                        setStatus(this.playerRef.getUuid(), "Finalizing world...");
                                        return service.terrainPlacer().finalizeSpawnAsync(generatedWorld, originX, originZ, terrainPackage, spawnSelection)
                                            .thenApply(ignored -> new GenerationResult(generatedWorld, spawnSelection.transform(), placed, waterPlaced, decorationsPlaced));
                                    });
                            });
                    });
            })
            .thenCompose(result -> {
                TerrainPackageDescriptor descriptor = job.packageDescriptor();
                CompletableFuture<TerrainPackageDescriptor> cleanupFuture;
                if (descriptor != null) {
                    cleanupFuture = CompletableFuture.runAsync(() -> service.deleteTerrainPackage(descriptor), service.requestExecutor())
                        .thenApply(ignored -> descriptor);
                } else {
                    cleanupFuture = CompletableFuture.completedFuture(null);
                }
                return cleanupFuture.thenApply(ignored -> result);
            })
            .thenCompose(result -> {
                job.transition(GenerationJobState.COMPLETE, "Generation complete. Preparing world...");
                addOutput("Generation complete. Preparing world...");
                World generatedWorld = result.generatedWorld();
                Transform spawnTransform = result.spawnTransform();

                if (endlessMode) {
                    setStatus(this.playerRef.getUuid(), "Starting AI Endless run...");
                    return LevelSessionManager.get().startAiEndlessRunForPlayer(this.playerRef, generatedWorld, spawnTransform)
                        .thenApply(ignored -> (Void) null);
                } else {
                    setStatus(this.playerRef.getUuid(), "Teleporting to generated world...");
                    World fromWorld = service.voidWorldManager().resolvePlayerWorld(this.playerRef);
                    if (fromWorld == null || generatedWorld == null) {
                        throw new IllegalStateException("Could not teleport: missing world reference");
                    }
                    return service.voidWorldManager().teleportPlayer(this.playerRef, fromWorld, generatedWorld, spawnTransform);
                }
            })
            .thenAccept(ignored -> {
                addOutput("Entered generated world!");
                setStatus(this.playerRef.getUuid(), "Generation complete.");
                isGenerating = false;
                close();
            })
            .exceptionally(throwable -> {
                String message = userFacingError(throwable);
                addOutput(message);
                setStatus(this.playerRef.getUuid(), message);
                isGenerating = false;
                return null;
            });
    }

    private String userFacingError(@Nonnull Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof ConnectException
                || current instanceof HttpConnectTimeoutException
                || current instanceof UnknownHostException) {
                return "Could not reach the AI terrain server. It may not be running.";
            }
            current = current.getCause();
        }
        return "Error: " + rootCause(throwable);
    }

    private String rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private record GenerationResult(
        @Nonnull World generatedWorld,
        @Nonnull Transform spawnTransform,
        int terrainColumns,
        int waterColumns,
        int decorationsPlaced
    ) {
    }

    private void addOutput(@Nonnull String message) {
        outputLines.addFirst(message);
        if (outputLines.size() > 5) {
            outputLines.removeLast();
        }
        pushUpdate(this.playerRef.getUuid());
    }

    private void clearOutput() {
        outputLines.clear();
    }

    private void setStatus(@Nonnull UUID playerUuid, @Nonnull String message) {
        statusByPlayer.put(playerUuid, message);
        pushUpdate(playerUuid);
    }

    private void writeOutput(@Nonnull UICommandBuilder ui) {
        ui.set("#OutputLine1.Text", statusByPlayer.getOrDefault(this.playerRef.getUuid(), "Ready to generate terrain."));
        String[] labels = {"#OutputLine2", "#OutputLine3", "#OutputLine4", "#OutputLine5"};
        int index = 0;
        for (String line : outputLines) {
            if (index < labels.length) {
                ui.set(labels[index] + ".Text", line);
                index++;
            } else {
                break;
            }
        }
        while (index < labels.length) {
            ui.set(labels[index] + ".Text", "");
            index++;
        }
    }

    @Nonnull
    private UICommandBuilder buildIncrementalUpdate() {
        UICommandBuilder ui = new UICommandBuilder();
        ui.set("#InputField.Value", draftInputs.getOrDefault(this.playerRef.getUuid(), ""));
        ui.set("#StrengthField.Value", draftStrengths.getOrDefault(this.playerRef.getUuid(), DEFAULT_STRENGTH_TEXT));
        ui.set("#SendBtn.Visible", !isGenerating);
        writeOutput(ui);
        return ui;
    }

    private static void pushUpdate(@Nonnull UUID playerUuid) {
        AiWorldGenPage page = activePages.get(playerUuid);
        if (page == null) {
            return;
        }
        World world;
        try {
            world = AiTerrainService.get().voidWorldManager().resolvePlayerWorld(page.playerRef);
        } catch (RuntimeException ignored) {
            return;
        }
        world.execute(() -> page.sendUpdate(page.buildIncrementalUpdate(), null, false));
    }

    @Nonnull
    private static String formatProgress(@Nonnull String label, int completedTiles, int totalTiles) {
        int percent = totalTiles <= 0 ? 100 : Math.max(0, Math.min(100, (completedTiles * 100) / totalTiles));
        return label + "... " + percent + "%";
    }

    @Nullable
    private static Float parseStrength(@Nullable String raw) {
        String value = raw == null ? DEFAULT_STRENGTH_TEXT : raw.trim();
        if (value.isEmpty()) {
            return 0.4f;
        }
        try {
            float parsed = Float.parseFloat(value);
            if (Float.isNaN(parsed) || Float.isInfinite(parsed) || parsed < 0.0f || parsed > 1.0f) {
                return null;
            }
            return parsed;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    @Nonnull
    private static String formatStrength(float value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }
}
