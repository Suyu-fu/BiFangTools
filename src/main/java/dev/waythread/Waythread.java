package dev.waythread;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Util;
import net.minecraft.util.context.ContextKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ExtractLevelRenderStateEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.client.settings.KeyModifier;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import org.joml.Matrix3x2fStack;
import org.joml.Quaternionf;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

@Mod(value = Waythread.MOD_ID, dist = Dist.CLIENT)
public final class Waythread {
    private static final Logger LOGGER = LogUtils.getLogger();
    static final String MOD_ID = "bifangtools";
    private static final KeyMapping.Category CATEGORY = new KeyMapping.Category(Identifier.fromNamespaceAndPath(MOD_ID, "waythread"));
    private static final Identifier RETURN_PROGRESS_LAYER = Identifier.fromNamespaceAndPath(MOD_ID, "return_progress");
    private static final ContextKey<AnchorRenderer.Marker> MARKER = new ContextKey<>(
            Identifier.fromNamespaceAndPath(MOD_ID, "waythread_marker"));
    private static final ContextKey<TrailParticles.AnchorQuads> ANCHOR_PARTICLES = new ContextKey<>(
            Identifier.fromNamespaceAndPath(MOD_ID, "waythread_anchor_particles"));
    private static final int CACHE_MARGIN = 6;
    private static final long CLEAR_HOLD_MS = 2_000;
    private static final long SAVE_INTERVAL_MS = 30_000;
    private static final int COMPLETED_PROGRESS_TICKS = 30;

    private final KeyMapping record = new KeyMapping("key.waythread.navigate",
            KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_J, CATEGORY);
    private final KeyMapping switchMode = new KeyMapping("key.waythread.switch_mode",
            KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, CATEGORY);
    private final KeyMapping toggleVisibility = new KeyMapping("key.waythread.toggle_visibility",
            KeyConflictContext.IN_GAME, KeyModifier.CONTROL, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_J, CATEGORY);
    private final KeyPress recordPress = new KeyPress(CLEAR_HOLD_MS);
    private final KeyPress modePress = new KeyPress(0);
    private final KeyPress visibilityPress = new KeyPress(0);
    private final Map<BreadcrumbTrail.Segment, List<Vec3>> localRoutes = new HashMap<>();
    private final TrailParticles particles = new TrailParticles();
    private final TrailSession session = new TrailSession();
    private final TrailSaveQueue saveQueue = new TrailSaveQueue();

    private ClientLevel level;
    private LocalPlayer player;
    private Vec3 lastPosition;
    private Path trailFile;
    private long nextSave;
    private boolean saveDirty;
    private BreadcrumbTrail searchedPath;
    private BreadcrumbTrail.Segment searchedSegment;
    private RouteSearch search;
    private boolean trailVisible = true;
    private int returnCompletedUntil;
    private boolean recoveryCompleted;

    public Waythread(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, WaythreadConfig.SPEC, "waythread-client.toml");
        container.registerExtensionPoint(IConfigScreenFactory.class, (mod, parent) ->
                new ConfigurationScreen(mod, parent, TrailConfigurationScreen::new));
        modBus.addListener(this::registerKeys);
        modBus.addListener(this::registerGuiLayers);
        modBus.addListener(AnchorRenderer::registerPipelines);
        modBus.addListener(TrailParticles::registerPipelines);
        NeoForge.EVENT_BUS.addListener(this::tick);
        NeoForge.EVENT_BUS.addListener(this::extractAnchor);
        NeoForge.EVENT_BUS.addListener(this::submitAnchor);
        NeoForge.EVENT_BUS.addListener(this::leaveWorld);
        NeoForge.EVENT_BUS.addListener(this::shutDown);
    }

    private void registerKeys(RegisterKeyMappingsEvent event) {
        event.registerCategory(CATEGORY);
        event.register(record);
        event.register(switchMode);
        event.register(toggleVisibility);
    }

    private void registerGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.OVERLAY_MESSAGE, RETURN_PROGRESS_LAYER, this::renderReturnProgress);
    }

    private void renderReturnProgress(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        if (player == null || !player.isAlive()) {
            return;
        }
        Mode mode = session.mode();
        int returnStartPointCount = session.returnStartPointCount();
        boolean returning = mode == Mode.RETURNING && returnStartPointCount > 0;
        boolean recovering = mode == Mode.RECOVERING;
        if (!returning && !recovering && player.tickCount >= returnCompletedUntil) {
            return;
        }
        boolean redProgress = recovering || (!returning && recoveryCompleted);
        boolean showBar = WaythreadConfig.SHOW_RETURN_PROGRESS_BAR.get();
        boolean showPercentage = WaythreadConfig.SHOW_RETURN_PERCENTAGE.get();
        if (!showBar && !showPercentage) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != player || minecraft.level != level || minecraft.options.hideGui) {
            return;
        }

        int percent = 100;
        if (returning) {
            percent = returnProgressPercent(returnStartPointCount, session.trail().pointCount());
        } else if (recovering) {
            DimensionalTrail trail = session.trail();
            percent = returnProgressPercent(trail.pointCount(), session.recovery().remainingPoints(trail));
        }
        int center = graphics.guiWidth() / 2;
        int y = graphics.guiHeight() - 82;
        if (showBar) {
            int width = 88;
            int left = center - width / 2;
            graphics.fill(left, y, left + width, y + 1, redProgress ? 0xB045171D : 0xB019232B);
            int filled = width * percent / 100;
            if (filled > 0) {
                graphics.fill(left, y, left + filled, y + 1, redProgress ? 0xFFFF515D : 0xFF6BE4F4);
            }
        }
        if (!showPercentage) {
            return;
        }
        int fontSize = WaythreadConfig.RETURN_PROGRESS_FONT_SIZE.get();
        float textScale = (float) fontSize / minecraft.font.lineHeight;
        Matrix3x2fStack pose = graphics.pose();
        pose.pushMatrix();
        pose.translate(center, y - fontSize - 2);
        pose.scale(textScale, textScale);
        String progressKey = redProgress ? "waythread.hud.recoveryProgress" : "waythread.hud.returnProgress";
        graphics.centeredText(minecraft.font, Component.translatable(progressKey, percent), 0, 0, 0xFFFFFFFF);
        pose.popMatrix();
    }

    static int returnProgressPercent(int startingPoints, int remainingPoints) {
        if (startingPoints <= 0) {
            return 0;
        }
        int removed = startingPoints - Math.clamp(remainingPoints, 0, startingPoints);
        return (int) (100L * removed / startingPoints);
    }

    private void tick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        boolean ready = updateWorld(minecraft);
        if (ready && player.isDeadOrDying()) {
            pauseForDeath();
        }
        boolean canUseKeys = ready && player.isAlive() && minecraft.screen == null;
        long now = Util.getMillis();
        boolean suppressRecord = handleVisibilityKey(canUseKeys, minecraft.getWindow(), now);
        handleRecordKey(canUseKeys && !suppressRecord, now);
        handleModeKey(canUseKeys, now);
        Mode mode = session.mode();
        if (!ready || mode == Mode.IDLE || !player.isAlive() || minecraft.isPaused()) {
            saveTrail(false);
            return;
        }

        DimensionalTrail trail = session.trail();
        RecoveryRoute recovery = session.recovery();
        Vec3 position = player.position();
        boolean moved = !position.equals(lastPosition);
        lastPosition = position;
        int previousPointCount = trail.pointCount();
        boolean returning = mode == Mode.RETURNING;
        boolean recovering = mode == Mode.RECOVERING;
        boolean waitingForRecovery = mode == Mode.WAITING_FOR_RECOVERY;
        boolean truncated = false;
        if (recovering) {
            RecoveryRoute.Step step = advanceRecovery(position);
            if (step == RecoveryRoute.Step.ARRIVED) {
                return;
            }
            truncated = step == RecoveryRoute.Step.ADVANCED;
        } else if (returning) {
            BreadcrumbTrail.RewindResult result = trail.rewind(level.dimension(), position,
                    WaythreadConfig.RETURN_ERASE_DISTANCE.get());
            if (result == BreadcrumbTrail.RewindResult.ARRIVED) {
                finishReturn();
                return;
            }
            if (!trail.waitingToRejoin()) {
                trail.setRecordingMode(WaythreadConfig.RECORDING_MODE.get());
                trail.trackReturnMovement(position, WaythreadConfig.RECORD_POINT_SPACING.get(),
                        WaythreadConfig.MAX_RECORDED_POINTS.get());
            }
            if (result == BreadcrumbTrail.RewindResult.BOUNDARY) {
                markTrailChanged();
                clearDisplay();
                showRejoinHint();
            } else if (result == BreadcrumbTrail.RewindResult.TRUNCATED) {
                truncated = true;
                BreadcrumbTrail path = trail.visiblePath(level.dimension());
                localRoutes.keySet().removeIf(segment -> !path.contains(segment));
                particles.clear();
            }
        } else if (mode == Mode.RECORDING) {
            recordPosition(position);
        }
        if ((returning || mode == Mode.RECORDING) && (moved || previousPointCount != trail.pointCount())) {
            markTrailChanged();
        }
        saveTrail(false);
        if (!trailVisible) {
            return;
        }
        BreadcrumbTrail path = waitingForRecovery ? null : recovering
                ? recovery.visiblePath(trail, level.dimension()) : trail.visiblePath(level.dimension());
        List<DimensionalTrail.Visit> localVisits = waitingForRecovery ? trail.visitsIn(level.dimension()) : List.of();
        if (path == null && localVisits.isEmpty()) {
            return;
        }

        int loadDistance = WaythreadConfig.LOAD_DISTANCE.get();
        double rangeSquared = loadDistance * loadDistance;
        TrailParticles.DisplayMode displayMode = WaythreadConfig.DISPLAY_MODE.get();
        boolean waypoints = displayMode == TrailParticles.DisplayMode.WAYPOINTS;
        if (waypoints) {
            clearSearch();
            localRoutes.clear();
        } else if (search != null) {
            if (!searchedSegment.isNear(position, rangeSquared)
                    || (!waitingForRecovery && searchedPath != path)
                    || !searchedPath.contains(searchedSegment)) {
                clearSearch();
            } else {
                List<Vec3> generated = search.advance();
                if (generated != null) {
                    localRoutes.put(searchedSegment, generated);
                    clearSearch();
                }
            }
        }
        boolean refresh = truncated || player.tickCount % 5 == 0;
        boolean showDirection = recovering || (returning ? !trail.waitingToRejoin()
                : mode == Mode.RECORDING && WaythreadConfig.SHOW_RECORDING_DIRECTION.get());
        if (!refresh && !showDirection) {
            return;
        }
        particles.setTick(player.tickCount);
        particles.updateColors(WaythreadConfig.TRAIL_COLOR.get(), WaythreadConfig.anchorColor());
        List<Vec3> tail = path == null ? List.of()
                : mode == Mode.RECORDING && !waypoints ? path.recordingTail() : path.current();
        if (refresh) {
            particles.updateAppearance(displayMode, WaythreadConfig.PARTICLE_STYLE.get(), WaythreadConfig.ANCHOR_APPEARANCE.get());
            if (!waypoints) {
                int cacheDistance = loadDistance + CACHE_MARGIN;
                localRoutes.keySet().removeIf(segment -> !segment.isNear(position, cacheDistance * cacheDistance));
            }
            particles.prune();
            TrailParticles.TrailDraw drawing = particles.startTrail(position, rangeSquared,
                    WaythreadConfig.TRAIL_PARTICLE_SPACING.get(), WaythreadConfig.TRAIL_PARTICLE_LIMIT.get(),
                    returning || recovering);
            if (waitingForRecovery) {
                for (DimensionalTrail.Visit visit : localVisits) {
                    if (!drawing.hasRoom()) {
                        break;
                    }
                    drawTrailPath(drawing, visit.path(), visit.path().current(), position, rangeSquared, waypoints);
                }
                RouteTarget marker = recovery.marker(trail, level.dimension());
                if (marker != null) {
                    drawStartParticle(marker.position(), position);
                }
            } else {
                drawTrailPath(drawing, path, tail, position, rangeSquared, waypoints);
                if (!recovering && trail.markerFor(path) == DimensionalTrail.Marker.START) {
                    drawStartParticle(path.anchor(), position);
                }
            }
        }
        if (recovering) {
            RouteTarget target = recovery.nextTarget(trail);
            if (target.isWithin(level.dimension(), position, rangeSquared)) {
                particles.highlight(target.position());
            }
        } else if (showDirection) {
            particles.pulse(path, tail, localRoutes, position, rangeSquared, player.tickCount, returning);
        }
    }

    private boolean updateWorld(Minecraft minecraft) {
        ClientLevel nextLevel = minecraft.level;
        LocalPlayer nextPlayer = minecraft.player;
        if (nextLevel == null || nextPlayer == null || minecraft.screen instanceof LevelLoadingScreen) {
            return false;
        }
        if (level == nextLevel && player == nextPlayer) {
            return true;
        }

        if (player == null || player.connection != nextPlayer.connection) {
            saveTrail(true);
            reset();
            level = nextLevel;
            player = nextPlayer;
            loadTrail(minecraft);
            return true;
        }

        ResourceKey<Level> from = level == null ? null : level.dimension();
        boolean died = player.isDeadOrDying() && nextPlayer != player;
        if (died) {
            pauseForDeath();
        }
        Mode mode = session.mode();
        boolean changedDimension = (mode == Mode.RECORDING || mode == Mode.RETURNING)
                && !died && !nextLevel.dimension().equals(from);
        level = nextLevel;
        player = nextPlayer;
        returnCompletedUntil = 0;
        recoveryCompleted = false;
        if (mode.isRecovery()) {
            lastPosition = player.position();
            clearDisplay();
            if (died) {
                showRecoveryStartHint();
            } else if (mode == Mode.RECOVERING) {
                showRecoveryAlignmentHint();
            }
            return true;
        }
        if (!changedDimension) {
            discardTrail();
            return true;
        }

        clearDisplay();
        markTrailChanged();
        Vec3 arrival = player.position();
        if (mode == Mode.RECORDING) {
            recordPosition(arrival);
        } else if (!session.trail().crossBack(from, lastPosition, level.dimension(), arrival,
                WaythreadConfig.RETURN_ERASE_DISTANCE.get())) {
            showRejoinHint();
        }
        lastPosition = arrival;
        return true;
    }

    private void leaveWorld(ClientPlayerNetworkEvent.LoggingOut event) {
        saveTrail(true);
        reset();
        level = null;
        player = null;
        trailFile = null;
        saveDirty = false;
    }

    private void shutDown(GameShuttingDownEvent event) {
        try {
            saveTrail(true);
        } finally {
            saveQueue.close();
        }
    }

    private void loadTrail(Minecraft minecraft) {
        trailFile = null;
        saveDirty = false;
        nextSave = Util.getMillis() + SAVE_INTERVAL_MS;
        IntegratedServer server = minecraft.getSingleplayerServer();
        if (server == null) {
            return;
        }
        try {
            trailFile = TrailSave.fileFor(FMLPaths.CONFIGDIR.get(), server.getWorldPath(LevelResource.ROOT),
                    server.getWorldGenSettings().options().seed());
            TrailSave saved = saveQueue.read(trailFile);
            if (saved == null) {
                return;
            }
            session.restore(saved);
            DimensionalTrail trail = session.trail();
            Mode mode = session.mode();
            lastPosition = player.position();
            if (mode == Mode.RECORDING || mode == Mode.RETURNING) {
                trail.restoreDimension(level.dimension(), mode == Mode.RETURNING);
            }
            if (mode == Mode.RETURNING && trail.waitingToRejoin()) {
                showRejoinHint();
            } else if (mode == Mode.WAITING_FOR_RECOVERY) {
                showRecoveryStartHint();
            } else if (mode == Mode.RECOVERING) {
                showRecoveryAlignmentHint();
            }
        } catch (IOException | RuntimeException error) {
            LOGGER.warn("Could not load the local Waythread route", error);
        }
    }

    private void markTrailChanged() {
        if (trailFile != null) {
            saveDirty = true;
        }
    }

    private void saveTrail(boolean force) {
        long now = Util.getMillis();
        if (!force && now < nextSave) {
            return;
        }
        nextSave = now + SAVE_INTERVAL_MS;
        if (trailFile != null && saveDirty) {
            saveQueue.submit(trailFile, session.snapshot());
            saveDirty = false;
        }
        saveQueue.retryFailed();
    }

    private void recordPosition(Vec3 position) {
        DimensionalTrail trail = session.trail();
        trail.setRecordingMode(WaythreadConfig.RECORDING_MODE.get());
        int pointLimit = WaythreadConfig.MAX_RECORDED_POINTS.get();
        if (trail.record(level.dimension(), position, WaythreadConfig.RECORD_POINT_SPACING.get(), pointLimit)) {
            message("point_limit", pointLimit);
        }
    }

    private void extractAnchor(ExtractLevelRenderStateEvent event) {
        Mode mode = session.mode();
        if (!trailVisible || mode == Mode.IDLE) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (event.getLevel() != level || minecraft.player != player || player == null || !player.isAlive()) {
            return;
        }
        Vec3 anchor;
        boolean relay;
        DimensionalTrail trail = session.trail();
        if (mode.isRecovery()) {
            RouteTarget target = session.recovery().marker(trail, level.dimension());
            if (target == null) {
                return;
            }
            anchor = target.position();
            relay = mode == Mode.RECOVERING;
        } else {
            BreadcrumbTrail path = trail.visiblePath(level.dimension());
            DimensionalTrail.Marker marker = trail.markerFor(path);
            if (marker == DimensionalTrail.Marker.NONE) {
                return;
            }
            anchor = path.anchor();
            relay = marker == DimensionalTrail.Marker.RELAY;
        }
        AnchorRenderer.Appearance appearance = WaythreadConfig.ANCHOR_APPEARANCE.get();
        boolean particleAnchor = !relay && appearance == AnchorRenderer.Appearance.PARTICLES;
        boolean seeThrough = WaythreadConfig.ANCHOR_SEE_THROUGH.get();
        if (particleAnchor && !seeThrough) {
            return;
        }
        int markerDistance = anchorDisplayDistance(seeThrough);
        if (anchor.distanceToSqr(player.position()) > markerDistance * markerDistance) {
            return;
        }
        float partialTick = event.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        float time = player.tickCount + partialTick;
        Vec3 center = particleAnchor ? anchor : AnchorRenderer.floatingCenter(anchor, time);
        Vec3 camera = event.getCamera().position();
        Vec3 displayPosition = seeThrough ? AnchorRenderer.seeThroughPosition(center, camera) : center;
        AABB markerBounds = new AABB(displayPosition.x - 0.75, displayPosition.y - 0.75, displayPosition.z - 0.75,
                displayPosition.x + 0.75, displayPosition.y + 1.45, displayPosition.z + 0.75);
        if (!event.getFrustum().isVisible(markerBounds)) {
            return;
        }
        if (particleAnchor) {
            TrailParticles.AnchorQuads quads = particles.extractSeeThroughAnchor(
                    event.getCamera(), partialTick, displayPosition.subtract(anchor));
            if (!quads.isEmpty()) {
                event.getRenderState().setRenderData(ANCHOR_PARTICLES, quads);
            }
            return;
        }
        String color = relay ? WaythreadConfig.TRAIL_COLOR.get() : WaythreadConfig.anchorColor();
        int rgb = Integer.parseInt(color, 1, color.length(), 16);
        AnchorRenderer.Marker marker = new AnchorRenderer.Marker(displayPosition.subtract(camera),
                relay ? AnchorRenderer.Appearance.ORB : appearance, seeThrough, rgb, time,
                relay ? new Quaternionf(event.getCamera().rotation()) : null);
        event.getRenderState().setRenderData(MARKER, marker);
    }

    private void submitAnchor(SubmitCustomGeometryEvent event) {
        AnchorRenderer.Marker marker = event.getLevelRenderState().getRenderData(MARKER);
        if (marker != null) {
            AnchorRenderer.submit(event, marker);
        }
        TrailParticles.AnchorQuads quads = event.getLevelRenderState().getRenderData(ANCHOR_PARTICLES);
        if (quads != null) {
            quads.submit(event);
        }
    }

    private static int anchorDisplayDistance(boolean seeThrough) {
        return seeThrough ? WaythreadConfig.ANCHOR_SEE_THROUGH_DISTANCE.get() : WaythreadConfig.LOAD_DISTANCE.get();
    }

    private boolean handleVisibilityKey(boolean canUseKeys, Window window, long now) {
        InputConstants.Key key = toggleVisibility.getKey();
        boolean sharedKey = record.getKey().equals(key) && !toggleVisibility.isUnbound();
        boolean captured = visibilityPress.capturesKey();
        // Releasing Ctrl first must not turn a held Ctrl+J into a plain J action.
        boolean primaryKeyHeld = sharedKey && captured && key.getType() == InputConstants.Type.KEYSYM
                && InputConstants.isKeyDown(window, key.getValue());
        boolean toggled = visibilityPress.update(consumeClicks(toggleVisibility),
                toggleVisibility.isDown() || primaryKeyHeld, canUseKeys, now) == KeyPress.Action.TAP;
        if (toggled) {
            trailVisible = !trailVisible;
            if (!trailVisible) {
                clearDisplay();
            }
            message(trailVisible ? "shown" : "hidden");
        }

        return sharedKey && (captured || toggled || visibilityPress.capturesKey());
    }

    private void handleRecordKey(boolean canUseKeys, long now) {
        switch (recordPress.update(consumeClicks(record), record.isDown(), canUseKeys, now)) {
            case HOLD -> {
                if (session.mode() == Mode.IDLE) {
                    return;
                }
                discardTrail();
                message("cleared");
            }
            case TAP -> {
                switch (session.mode()) {
                    case IDLE, ARRIVED -> startRecording();
                    case RECORDING -> message("recording");
                    case RETURNING -> {
                        if (session.trail().waitingToRejoin()) {
                            resumeRecording();
                        } else {
                            message("returning");
                        }
                    }
                    case WAITING_FOR_RECOVERY -> showRecoveryStartHint();
                    case RECOVERING -> message("recovering");
                }
            }
            case NONE -> {
            }
        }
    }

    private void handleModeKey(boolean canUseKeys, long now) {
        if (modePress.update(consumeClicks(switchMode), switchMode.isDown(), canUseKeys, now) != KeyPress.Action.TAP) {
            return;
        }
        switch (session.mode()) {
            case IDLE, ARRIVED -> message("no_trail");
            case RECORDING, RETURNING -> toggleReturn();
            case WAITING_FOR_RECOVERY -> startRecovery();
            case RECOVERING -> rejoinRecovery();
        }
    }

    private static boolean consumeClicks(KeyMapping key) {
        boolean clicked = false;
        while (key.consumeClick()) {
            clicked = true;
        }
        return clicked;
    }

    private void startRecording() {
        reset();
        session.startRecording(level.dimension(), player.position());
        markTrailChanged();
        message("marked");
    }

    private void startRecovery() {
        if (!session.startRecovery(level.dimension(), player.position(), WaythreadConfig.RETURN_ERASE_DISTANCE.get())) {
            message("recovery_wait");
            return;
        }
        returnCompletedUntil = 0;
        markTrailChanged();
        clearDisplay();
        message("recovering");
    }

    private void pauseForDeath() {
        boolean paused = session.pauseForDeath(level.dimension(), player.position(), WaythreadConfig.RECORDING_MODE.get(),
                WaythreadConfig.RECORD_POINT_SPACING.get(), WaythreadConfig.MAX_RECORDED_POINTS.get(),
                WaythreadConfig.RETURN_ERASE_DISTANCE.get(), new WalkableTerrain(level)::canJoin);
        if (!paused) {
            return;
        }
        returnCompletedUntil = 0;
        recoveryCompleted = false;
        markTrailChanged();
        saveTrail(true);
        clearDisplay();
    }

    private void showRecoveryStartHint() {
        message("recovery_start", switchMode.getTranslatedKeyMessage());
    }

    private RecoveryRoute.Step advanceRecovery(Vec3 position) {
        DimensionalTrail trail = session.trail();
        RecoveryRoute recovery = session.recovery();
        BreadcrumbTrail previous = recovery.visiblePath(trail, level.dimension());
        RecoveryRoute.Step step = recovery.advance(trail, level.dimension(), position,
                WaythreadConfig.RETURN_ERASE_DISTANCE.get());
        switch (step) {
            case WAITING -> {
            }
            case ADVANCED -> {
                if (previous != recovery.visiblePath(trail, level.dimension())) {
                    clearDisplay();
                } else {
                    particles.clear();
                }
                markTrailChanged();
                showRecoveryAlignmentHint();
            }
            case ARRIVED -> finishRecovery(position);
        }
        return step;
    }

    private void finishRecovery(Vec3 position) {
        session.finishRecovery(level.dimension(), position, WaythreadConfig.MAX_RECORDED_POINTS.get(),
                WaythreadConfig.RETURN_ERASE_DISTANCE.get(), new WalkableTerrain(level)::canJoin);
        completeReturn(true);
    }

    private void rejoinRecovery() {
        if (session.recovery().rejoin(session.trail(), level.dimension(), player.position(),
                WaythreadConfig.RETURN_ERASE_DISTANCE.get())) {
            clearDisplay();
            markTrailChanged();
            message("recovering");
        }
        showRecoveryAlignmentHint();
    }

    private void showRecoveryAlignmentHint() {
        DimensionalTrail trail = session.trail();
        RecoveryRoute recovery = session.recovery();
        if (recovery.rejoinTarget(trail, level.dimension()) != null) {
            message("recovery_rejoin", switchMode.getTranslatedKeyMessage());
            return;
        }
        RouteAlignment alignment = recovery.alignment(trail, level.dimension());
        if (alignment.waitingToRejoin()) {
            showAlignmentHint(alignment, recovery.nextTarget(trail).dimension());
        }
    }

    private void toggleReturn() {
        if (session.mode() == Mode.RETURNING) {
            if (session.trail().waitingToRejoin()) {
                rejoin();
            } else {
                resumeRecording();
            }
        } else if (session.beginReturn(level.dimension(), WaythreadConfig.MAX_RECORDED_POINTS.get())) {
            recoveryCompleted = false;
            returnCompletedUntil = 0;
            markTrailChanged();
            particles.clear();
            if (session.trail().waitingToRejoin()) {
                showRejoinHint();
            } else {
                message("returning");
            }
        } else {
            message("no_trail");
        }
    }

    private void rejoin() {
        DimensionalTrail trail = session.trail();
        switch (trail.rejoin(level.dimension(), player.position(), WaythreadConfig.RETURN_ERASE_DISTANCE.get())) {
            case ARRIVED -> finishReturn();
            case REJOINED -> {
                markTrailChanged();
                clearDisplay();
                if (trail.waitingToRejoin()) {
                    showRejoinHint();
                } else {
                    message("returning");
                }
            }
            case NO_MATCH -> showRejoinHint();
            case OVERLAP -> message("rejoin_overlap");
        }
    }

    private void showRejoinHint() {
        DimensionalTrail trail = session.trail();
        showAlignmentHint(trail.returnAlignment(), trail.boundaryDestination());
    }

    private void showAlignmentHint(RouteAlignment alignment, ResourceKey<Level> destination) {
        if (!alignment.waitingToRejoin()) {
            return;
        }
        boolean recovering = session.mode() == Mode.RECOVERING;
        if (destination != null && !destination.equals(level.dimension())) {
            message(recovering ? "recovery_dimension" : "return_dimension", dimensionName(destination));
        } else if (!recovering) {
            message("rejoin", switchMode.getTranslatedKeyMessage());
        }
    }

    private static Component dimensionName(ResourceKey<Level> dimension) {
        if (dimension.equals(Level.OVERWORLD)) {
            return Component.translatable("flat_world_preset.minecraft.overworld");
        }
        if (dimension.equals(Level.NETHER)) {
            return Component.translatable("advancements.nether.root.title");
        }
        if (dimension.equals(Level.END)) {
            return Component.translatable("advancements.end.root.title");
        }
        return Component.literal(dimension.identifier().toString());
    }

    private void resumeRecording() {
        session.resumeRecording(level.dimension(), player.position(), WaythreadConfig.RECORDING_MODE.get(),
                WaythreadConfig.RECORD_POINT_SPACING.get(),
                WaythreadConfig.MAX_RECORDED_POINTS.get(), WaythreadConfig.RETURN_ERASE_DISTANCE.get(),
                new WalkableTerrain(level)::canJoin);
        markTrailChanged();
        clearDisplay();
        message("resumed");
    }

    private void finishReturn() {
        session.finishReturn(WaythreadConfig.CLEAR_ON_ARRIVAL.get());
        resetClientProgress();
        completeReturn(false);
    }

    private void completeReturn(boolean recovering) {
        recoveryCompleted = recovering;
        returnCompletedUntil = player.tickCount + COMPLETED_PROGRESS_TICKS;
        clearDisplay();
        markTrailChanged();
        saveTrail(true);
        if (WaythreadConfig.SHOW_ARRIVAL_MESSAGE.get()) {
            notifyArrival(recovering ? "recovery_arrived" : "arrived");
        }
    }

    private void notifyArrival(String key) {
        message(key);
        Minecraft.getInstance().getSoundManager().play(
                SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0F, 0.5F));
    }

    private void drawTrailPath(TrailParticles.TrailDraw drawing, BreadcrumbTrail path, List<Vec3> tail,
            Vec3 position, double rangeSquared, boolean waypoints) {
        drawing.path(tail, path.currentStartDistance(), 0);
        List<BreadcrumbTrail.Segment> segments = path.segments();
        int firstPoint = session.mode() == Mode.RECOVERING ? session.recovery().pointIndex() : 0;
        for (int index = segments.size() - 1; index >= 0 && drawing.hasRoom(); index--) {
            BreadcrumbTrail.Segment segment = segments.get(index);
            if (segment.endIndex() < firstPoint) {
                break;
            }
            List<Vec3> points = segment.samples();
            if (!waypoints) {
                if (!segment.isNear(position, rangeSquared)) {
                    continue;
                }
                List<Vec3> generated = localRoutes.get(segment);
                if (generated != null) {
                    points = generated;
                } else if (search == null) {
                    searchedPath = path;
                    searchedSegment = segment;
                    search = new RouteSearch(new WalkableTerrain(level), segment);
                }
            }
            drawing.path(points, segment.startDistance(), segment.length());
        }
    }

    private void drawStartParticle(Vec3 anchor, Vec3 position) {
        int markerDistance = anchorDisplayDistance(WaythreadConfig.ANCHOR_SEE_THROUGH.get());
        if (anchor.distanceToSqr(position) <= markerDistance * markerDistance) {
            particles.anchor(anchor);
        }
    }

    private void discardTrail() {
        boolean hadRoute = session.mode() != Mode.IDLE;
        reset();
        if (hadRoute) {
            markTrailChanged();
            saveTrail(true);
        }
    }

    private void reset() {
        session.clear();
        resetClientProgress();
        clearDisplay();
    }

    private void resetClientProgress() {
        lastPosition = null;
        returnCompletedUntil = 0;
        recoveryCompleted = false;
    }

    private void clearDisplay() {
        clearSearch();
        localRoutes.clear();
        particles.clear();
    }

    private void clearSearch() {
        searchedPath = null;
        searchedSegment = null;
        search = null;
    }

    private void message(String key, Object... args) {
        Minecraft.getInstance().gui.setOverlayMessage(Component.translatable("message.waythread." + key, args), false);
    }

    enum Mode {
        IDLE, RECORDING, RETURNING, ARRIVED, WAITING_FOR_RECOVERY, RECOVERING;

        boolean isRecovery() {
            return this == WAITING_FOR_RECOVERY || this == RECOVERING;
        }
    }
}
