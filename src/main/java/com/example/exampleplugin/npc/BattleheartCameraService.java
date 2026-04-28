package com.example.exampleplugin.npc;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.protocol.PositionType;
import com.hypixel.hytale.protocol.ClientCameraView;
import com.hypixel.hytale.protocol.Direction;
import com.hypixel.hytale.protocol.ApplyLookType;
import com.hypixel.hytale.protocol.AttachedToType;
import com.hypixel.hytale.protocol.MouseInputType;
import com.hypixel.hytale.protocol.MouseInputTargetType;
import com.hypixel.hytale.protocol.MovementForceRotationType;
import com.hypixel.hytale.protocol.Position;
import com.hypixel.hytale.protocol.PositionDistanceOffsetType;
import com.hypixel.hytale.protocol.RotationType;
import com.hypixel.hytale.protocol.ServerCameraSettings;
import com.hypixel.hytale.protocol.Vector2f;
import com.hypixel.hytale.protocol.Vector3f;
import com.hypixel.hytale.protocol.packets.camera.SetServerCamera;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.universe.world.World;

import javax.annotation.Nonnull;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.hypixel.hytale.server.core.entity.Frozen;
import com.hypixel.hytale.server.core.universe.Universe;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class BattleheartCameraService {
    private static final ConcurrentHashMap<UUID, CameraMode> APPLIED_MODES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, ScheduledFuture<?>> BOOTSTRAP_TASKS = new ConcurrentHashMap<>();
    private static final int CAMERA_BOOTSTRAP_MAX_RUNS = 80;
    private static final long CAMERA_BOOTSTRAP_PERIOD_MS = 100L;
    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor();

    private BattleheartCameraService() {
    }

    public static void applyArenaCamera(@Nonnull PlayerRef playerRef) {
        stopBootstrap(playerRef.getUuid());
        ServerCameraSettings cameraSettings = new ServerCameraSettings();
        cameraSettings.positionLerpSpeed = 0.1F;
        cameraSettings.rotationLerpSpeed = 0.14F;
        cameraSettings.distance = 18.0F;
        cameraSettings.displayCursor = true;
        cameraSettings.displayReticle = true;
        cameraSettings.isFirstPerson = false;
        cameraSettings.movementForceRotationType = MovementForceRotationType.Custom;
        cameraSettings.eyeOffset = true;
        
        cameraSettings.attachedToType = AttachedToType.LocalPlayer;
        cameraSettings.positionType = PositionType.AttachedToPlusOffset;
        cameraSettings.positionDistanceOffsetType = PositionDistanceOffsetType.DistanceOffset;
        cameraSettings.positionOffset = new Position(1.0D, 1.25D, 0.0D);
        
        cameraSettings.rotationType = RotationType.Custom;
        cameraSettings.rotation = new Direction(0.0F, (float)(-Math.PI / 2.6), 0.0F);
        
        cameraSettings.mouseInputType = MouseInputType.LookAtPlane;
        cameraSettings.planeNormal = new Vector3f(0.0F, 1.0F, 0.0F);
        
        playerRef.getPacketHandler().writeNoCache(new SetServerCamera(ClientCameraView.Custom, true, cameraSettings));
        APPLIED_MODES.put(playerRef.getUuid(), CameraMode.OVERVIEW);
    }

    public static void applyPreferredCamera(@Nonnull PlayerRef playerRef) {
        applyPreferredCamera(playerRef, false);
    }

    public static void applyPreferredCamera(@Nonnull PlayerRef playerRef, boolean force) {
        if (!force && APPLIED_MODES.get(playerRef.getUuid()) == CameraMode.LOBBY) {
            return;
        }
        CameraMode desiredMode = BattleheartCameraPreferences.isThirdPersonCameraEnabled(playerRef.getUuid())
            ? CameraMode.THIRD_PERSON
            : CameraMode.OVERVIEW;
        if (!force && APPLIED_MODES.get(playerRef.getUuid()) == desiredMode) {
            return;
        }
        if (desiredMode == CameraMode.OVERVIEW) {
            applyArenaCamera(playerRef);
        } else {
            applyThirdPersonCamera(playerRef);
        }
    }

    public static void applyThirdPersonCamera(@Nonnull PlayerRef playerRef) {
        stopBootstrap(playerRef.getUuid());
        ServerCameraSettings cameraSettings = new ServerCameraSettings();
        cameraSettings.displayReticle = true;
        cameraSettings.isFirstPerson = false;
        cameraSettings.positionOffset = new Position(0.45D, 0.35D, 0.0D);
        cameraSettings.mouseInputType = MouseInputType.LookAtTarget;
        playerRef.getPacketHandler().writeNoCache(new SetServerCamera(ClientCameraView.ThirdPerson, false, cameraSettings));
        APPLIED_MODES.put(playerRef.getUuid(), CameraMode.THIRD_PERSON);
    }

    public static void applyMenuCamera(@Nonnull PlayerRef playerRef) {
        if (APPLIED_MODES.get(playerRef.getUuid()) == CameraMode.LOBBY) {
            return;
        }
        APPLIED_MODES.put(playerRef.getUuid(), CameraMode.LOBBY);
        stopBootstrap(playerRef.getUuid());

        System.out.println("[Battleheart] Starting applyMenuCamera for " + playerRef.getUuid());
        
        // 1. Force break any first-person lock immediately
        playerRef.getPacketHandler().writeNoCache(new SetServerCamera(ClientCameraView.ThirdPerson, false, new ServerCameraSettings()));

        // 2. Trigger Bootstrap Camera Updates
        AtomicInteger runs = new AtomicInteger(0);
        ScheduledFuture<?> task = SCHEDULER.scheduleAtFixedRate(() -> {
            try {
                Ref<EntityStore> ref = playerRef.getReference();
                int currentRun = runs.getAndIncrement();
                
                if (currentRun >= CAMERA_BOOTSTRAP_MAX_RUNS) {
                    System.out.println("[Battleheart] Bootstrap completed for " + playerRef.getUuid());
                    stopBootstrap(playerRef.getUuid());
                    return;
                }

                if (ref == null || !ref.isValid()) {
                    if (currentRun % 10 == 0) {
                        System.out.println("[Battleheart] Waiting for valid reference for " + playerRef.getUuid() + " (Run " + currentRun + ")");
                    }
                    return;
                }

                Store<EntityStore> store = ref.getStore();
                if (store == null || store.getExternalData() == null || store.getExternalData().getWorld() == null) {
                    return;
                }

                World world = store.getExternalData().getWorld();
                world.execute(() -> {
                    Ref<EntityStore> liveRef = playerRef.getReference();
                    if (liveRef == null || !liveRef.isValid()) return;
                    Store<EntityStore> liveStore = liveRef.getStore();
                    setupLobbyCamera(liveStore, liveRef, playerRef, currentRun > 0);
                });
            } catch (Exception e) {
                System.err.println("[Battleheart] Error in camera bootstrap task: " + e.getMessage());
                e.printStackTrace();
            }
        }, 250L, CAMERA_BOOTSTRAP_PERIOD_MS, TimeUnit.MILLISECONDS);
        
        BOOTSTRAP_TASKS.put(playerRef.getUuid(), task);
    }

    private static void stopBootstrap(UUID uuid) {
        ScheduledFuture<?> existing = BOOTSTRAP_TASKS.remove(uuid);
        if (existing != null) {
            existing.cancel(false);
        }
    }

    private static void setupLobbyCamera(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef playerRef, boolean smooth) {
        if (APPLIED_MODES.get(playerRef.getUuid()) != CameraMode.LOBBY) {
            return;
        }

        TransformComponent tc = store.getComponent(ref, TransformComponent.getComponentType());
        if (tc == null) {
            if (!smooth) System.out.println("[Battleheart] setupLobbyCamera failed: TransformComponent missing.");
            return;
        }

        Vector3d playerPos = tc.getPosition();
        float yaw = tc.getRotation().getYaw();
        Vector3d forward = Transform.getDirection(0.0f, yaw);
        Vector3d right = new Vector3d(-forward.getZ(), 0, forward.getX());
        Vector3d camPos = new Vector3d(
            playerPos.getX() - forward.getX() * 2.5 + right.getX() * 0.5, 
            playerPos.getY() + 1.2, 
            playerPos.getZ() - forward.getZ() * 2.5 + right.getZ() * 0.5
        );

        ServerCameraSettings settings = new ServerCameraSettings();
        settings.positionLerpSpeed = smooth ? 0.08f : 1.0f;
        settings.rotationLerpSpeed = smooth ? 0.08f : 1.0f;

        settings.mouseInputType = MouseInputType.LookAtTarget;
        settings.mouseInputTargetType = MouseInputTargetType.None;
        settings.allowPitchControls = false;
        settings.isFirstPerson = false;
        settings.displayReticle = false;
        settings.displayCursor = true;
        
        // COMPLETELY DETACH from player to ensure static position/rotation
        settings.attachedToType = AttachedToType.None;
        
        settings.positionType = PositionType.Custom;
        settings.position = new Position(camPos.getX(), camPos.getY(), camPos.getZ());
        
        settings.rotationType = RotationType.Custom;
        settings.rotation = new Direction(yaw, 0.1F, 0.0F);
        
        settings.eyeOffset = false;
        settings.distance = 0.0f;

        // Disable all character/mouse influence
        settings.movementForceRotationType = MovementForceRotationType.Custom;
        settings.applyLookType = ApplyLookType.Rotation;
        settings.lookMultiplier = new Vector2f(0.0F, 0.0F);
        settings.sendMouseMotion = false;

        playerRef.getPacketHandler().writeNoCache(new SetServerCamera(ClientCameraView.Custom, true, settings));
    }

    private enum CameraMode {
        OVERVIEW,
        THIRD_PERSON,
        LOBBY
    }
}
