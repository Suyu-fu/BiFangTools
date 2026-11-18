package dev.waythread;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.Util;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
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
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.client.settings.KeyModifier;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

@Mod(value = Waythread.MOD_ID, dist = Dist.CLIENT)
public final class Waythread {
    private static final Logger LOGGER = LogUtils.getLogger();
    static final String MOD_ID = "bifangs_bag_of_curiosities";
    private static final String CATEGORY = "key.categories.waythread";
    private static final ResourceLocation RETURN_PROGRESS_LAYER = ResourceLocation.fromNamespaceAndPath(MOD_ID, "return_progress");
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
    private final Map<BreadcrumbTrail.Segment, List<Vec3>> localRoutes = new HashMap<>();
    private final TrailParticles particles = new TrailParticles();

    private ClientLevel level;
    private LocalPlayer player;
    private DimensionalTrail trail;
    private Vec3 lastPosition;
    private Path trailFile;
    private long nextSave;
    private boolean saveDirty;
    private BreadcrumbTrail.Segment searchedSegment;
    private RouteSearch search;
    private Mode mode = Mode.RECORDING;
    private long recordPressedAt = -1;
    private boolean holdHandled;
    private boolean modeKeyHandled;
    private boolean visibilityKeyHandled;
    private boolean trailVisible = true;
    private int returnStartPointCount;
    private int returnCompletedUntil;

    public Waythread(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, WaythreadConfig.SPEC, "waythread-client.toml");
        container.registerExtensionPoint(IConfigScreenFactory.class, (mod, parent) ->
                new ConfigurationScreen(mod, parent, TrailConfigurationScreen::new));
        modBus.addListener(this::registerKeys);
        modBus.addListener(this::registerGuiLayers);
        NeoForge.EVENT_BUS.addListener(this::tick);
        NeoForge.EVENT_BUS.addListener(this::renderAnchor);
        NeoForge.EVENT_BUS.addListener(this::leaveWorld);
        NeoForge.EVENT_BUS.addListener(this::shutDown);
    }

    private void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(record);
        event.register(switchMode);
        event.register(toggleVisibility);
    }

    private void registerGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.OVERLAY_MESSAGE, RETURN_PROGRESS_LAYER, this::renderReturnProgress);
    }

    private void renderReturnProgress(GuiGraphics graphics, DeltaTracker deltaTracker) {
        if (player == null || !player.isAlive()) {
            return;
        }
        boolean returning = mode == Mode.RETURNING && trail != null && returnStartPointCount > 0;
        if (!returning && player.tickCount >= returnCompletedUntil) {
            return;
        }
        boolean showBar = WaythreadConfig.SHOW_RETURN_PROGRESS_BAR.get();
        boolean showPercentage = WaythreadConfig.SHOW_RETURN_PERCENTAGE.get();
        if (!showBar && !showPercentage) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != player || minecraft.level != level || minecraft.options.hideGui) {
            return;
        }

        int percent = returning ? returnProgressPercent(returnStartPointCount, trail.pointCount()) : 100;
        int center = graphics.guiWidth() / 2;
        int y = graphics.guiHeight() - 82;
        if (showBar) {
            int width = 88;
            int left = center - width / 2;
            graphics.fill(left, y, left + width, y + 1, 0xB019232B);
            int filled = width * percent / 100;
            if (filled > 0) {
                graphics.fill(left, y, left + filled, y + 1, 0xFF6BE4F4);
            }
        }
        if (!showPercentage) {
            return;
        }
        int fontSize = WaythreadConfig.RETURN_PROGRESS_FONT_SIZE.get();
        float textScale = (float) fontSize / minecraft.font.lineHeight;
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(center, y - fontSize - 2, 0);
        pose.scale(textScale, textScale, 1.0F);
        graphics.drawCenteredString(minecraft.font, Component.translatable("waythread.hud.returnProgress", percent),
                0, 0, 0xFFFFFFFF);
        pose.popPose();
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
        boolean canUseKeys = ready && player.isAlive() && minecraft.screen == null;
        boolean suppressRecord = handleVisibilityKey(canUseKeys, minecraft.getWindow().getWindow());
        handleRecordKey(canUseKeys && !suppressRecord);
        while (switchMode.consumeClick()) {
            if (canUseKeys && !modeKeyHandled) {
                toggleReturn();
                modeKeyHandled = true;
            }
        }
        if (!switchMode.isDown()) {
            modeKeyHandled = false;
        }
        if (!ready || trail == null || !player.isAlive() || minecraft.isPaused()) {
            return;
        }

        Vec3 position = player.position();
        boolean moved = !position.equals(lastPosition);
        lastPosition = position;
        int previousPointCount = trail.pointCount();
        boolean returning = mode == Mode.RETURNING;
        boolean truncated = false;
        if (returning) {
            BreadcrumbTrail.RewindResult result = trail.rewind(level.dimension(), position,
                    WaythreadConfig.RETURN_ERASE_DISTANCE.get());
            if (result == BreadcrumbTrail.RewindResult.ARRIVED) {
                finishReturn();
                return;
            }
            if (!trail.waitingToRejoin()) {
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
        if (mode != Mode.ARRIVED && (moved || previousPointCount != trail.pointCount())) {
            markTrailChanged();
        }
        saveTrail(false);
        BreadcrumbTrail path = trail.visiblePath(level.dimension());
        if (!trailVisible || path == null) {
            return;
        }

        int loadDistance = WaythreadConfig.LOAD_DISTANCE.get();
        double rangeSquared = loadDistance * loadDistance;
        TrailParticles.DisplayMode displayMode = WaythreadConfig.DISPLAY_MODE.get();
        boolean waypoints = displayMode == TrailParticles.DisplayMode.WAYPOINTS;
        if (waypoints) {
            searchedSegment = null;
            search = null;
            localRoutes.clear();
        } else if (search != null) {
            if (!searchedSegment.isNear(position, rangeSquared)
                    || !path.contains(searchedSegment)) {
                search = null;
                searchedSegment = null;
            } else {
                List<Vec3> generated = search.advance();
                if (generated != null) {
                    localRoutes.put(searchedSegment, generated);
                    search = null;
                    searchedSegment = null;
                }
            }
        }
        boolean refresh = truncated || player.tickCount % 5 == 0;
        boolean showDirection = returning ? !trail.waitingToRejoin()
                : mode == Mode.RECORDING && WaythreadConfig.SHOW_RECORDING_DIRECTION.get();
        if (!refresh && !showDirection) {
            return;
        }
        particles.updateColors(WaythreadConfig.TRAIL_COLOR.get(), WaythreadConfig.anchorColor());
        List<Vec3> tail = mode == Mode.RECORDING && !waypoints ? path.recordingTail() : path.current();
        if (refresh) {
            particles.updateAppearance(displayMode, WaythreadConfig.PARTICLE_STYLE.get(), WaythreadConfig.ANCHOR_APPEARANCE.get());
            if (!waypoints) {
                int cacheDistance = loadDistance + CACHE_MARGIN;
                localRoutes.keySet().removeIf(segment -> !segment.isNear(position, cacheDistance * cacheDistance));
            }
            showTrail(path, tail, position, rangeSquared, waypoints);
        }
        if (showDirection) {
            particles.pulse(path, tail, localRoutes, position, rangeSquared, player.tickCount, returning);
        }
    }

    private boolean updateWorld(Minecraft minecraft) {
        ClientLevel nextLevel = minecraft.level;
        LocalPlayer nextPlayer = minecraft.player;
        if (nextLevel == null || nextPlayer == null || minecraft.screen instanceof ReceivingLevelScreen) {
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
        boolean changedDimension = trail != null && !player.isDeadOrDying()
                && mode != Mode.ARRIVED && !nextLevel.dimension().equals(from);
        level = nextLevel;
        player = nextPlayer;
        if (!changedDimension) {
            discardTrail();
            return true;
        }

        clearDisplay();
        markTrailChanged();
        Vec3 arrival = player.position();
        if (mode == Mode.RECORDING) {
            recordPosition(arrival);
        } else if (!trail.crossBack(from, lastPosition, level.dimension(), arrival,
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
        saveTrail(true);
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
                    server.getWorldData().worldGenOptions().seed());
            TrailSave saved = TrailSave.read(trailFile);
            if (saved == null) {
                return;
            }
            trail = saved.trail();
            mode = saved.mode();
            returnStartPointCount = saved.returnStartPointCount();
            lastPosition = player.position();
            if (mode != Mode.ARRIVED) {
                trail.restoreDimension(level.dimension(), mode == Mode.RETURNING);
            }
            if (mode == Mode.RETURNING && trail.waitingToRejoin()) {
                showRejoinHint();
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
        if (trailFile == null || !saveDirty || (!force && Util.getMillis() < nextSave)) {
            return;
        }
        nextSave = Util.getMillis() + SAVE_INTERVAL_MS;
        try {
            if (trail == null) {
                Files.deleteIfExists(trailFile);
            } else {
                new TrailSave(trail, mode, returnStartPointCount).write(trailFile);
            }
            saveDirty = false;
        } catch (IOException error) {
            LOGGER.warn("Could not save the local Waythread route", error);
        }
    }

    private void recordPosition(Vec3 position) {
        int pointLimit = WaythreadConfig.MAX_RECORDED_POINTS.get();
        if (trail.record(level.dimension(), position, WaythreadConfig.RECORD_POINT_SPACING.get(), pointLimit)) {
            message("point_limit", pointLimit);
        }
    }

    private void renderAnchor(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL || !trailVisible || trail == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != level || minecraft.player != player || player == null || !player.isAlive()) {
            return;
        }
        BreadcrumbTrail path = trail.visiblePath(level.dimension());
        DimensionalTrail.Marker marker = trail.markerFor(path);
        if (marker == DimensionalTrail.Marker.NONE) {
            return;
        }
        AnchorRenderer.Appearance appearance = WaythreadConfig.ANCHOR_APPEARANCE.get();
        boolean particleAnchor = marker == DimensionalTrail.Marker.START && appearance == AnchorRenderer.Appearance.PARTICLES;
        boolean seeThrough = WaythreadConfig.ANCHOR_SEE_THROUGH.get();
        if (particleAnchor && !seeThrough) {
            return;
        }
        Vec3 anchor = path.anchor();
        int markerDistance = anchorDisplayDistance(seeThrough);
        if (anchor.distanceToSqr(player.position()) > markerDistance * markerDistance) {
            return;
        }
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        float time = player.tickCount + partialTick;
        Vec3 center = particleAnchor ? anchor : AnchorRenderer.floatingCenter(anchor, time);
        Vec3 displayPosition = seeThrough ? AnchorRenderer.seeThroughPosition(center, event.getCamera().getPosition()) : center;
        AABB markerBounds = new AABB(displayPosition.x - 0.75, displayPosition.y - 0.75, displayPosition.z - 0.75,
                displayPosition.x + 0.75, displayPosition.y + 1.45, displayPosition.z + 0.75);
        if (!event.getFrustum().isVisible(markerBounds)) {
            return;
        }
        if (particleAnchor) {
            particles.renderSeeThroughAnchor(event, partialTick, displayPosition.subtract(anchor));
            return;
        }
        boolean relay = marker == DimensionalTrail.Marker.RELAY;
        String color = relay ? WaythreadConfig.TRAIL_COLOR.get() : WaythreadConfig.anchorColor();
        int rgb = Integer.parseInt(color, 1, color.length(), 16);
        if (relay) {
            AnchorRenderer.renderRelay(event, displayPosition, seeThrough, rgb, time);
        } else {
            AnchorRenderer.render(event, displayPosition, appearance, seeThrough, rgb, time);
        }
    }

    private static int anchorDisplayDistance(boolean seeThrough) {
        return seeThrough ? WaythreadConfig.ANCHOR_SEE_THROUGH_DISTANCE.get() : WaythreadConfig.LOAD_DISTANCE.get();
    }

    private boolean handleVisibilityKey(boolean canUseKeys, long window) {
        boolean clicked = false;
        while (toggleVisibility.consumeClick()) {
            clicked = true;
        }
        if (canUseKeys && clicked && !visibilityKeyHandled) {
            visibilityKeyHandled = true;
            trailVisible = !trailVisible;
            if (!trailVisible) {
                clearDisplay();
            }
            message(trailVisible ? "shown" : "hidden");
        }

        InputConstants.Key key = toggleVisibility.getKey();
        boolean suppressRecord = visibilityKeyHandled && record.getKey().equals(key);
        // Releasing Ctrl first must not turn a held Ctrl+J into a plain J action.
        boolean primaryKeyHeld = suppressRecord && key.getType() == InputConstants.Type.KEYSYM
                && InputConstants.isKeyDown(window, key.getValue());
        if (!primaryKeyHeld && !toggleVisibility.isDown()) {
            visibilityKeyHandled = false;
        }
        return suppressRecord;
    }

    private void handleRecordKey(boolean canUseKeys) {
        boolean clicked = false;
        while (record.consumeClick()) {
            clicked = true;
        }
        if (!canUseKeys) {
            recordPressedAt = -1;
            holdHandled = false;
            return;
        }

        long now = Util.getMillis();
        if (record.isDown() && clicked && recordPressedAt < 0) {
            recordPressedAt = now;
        }
        if (recordPressedAt >= 0 && !holdHandled && now - recordPressedAt >= CLEAR_HOLD_MS) {
            if (trail != null) {
                discardTrail();
                message("cleared");
            }
            holdHandled = true;
        }
        if (!record.isDown()) {
            if (!holdHandled && (clicked || recordPressedAt >= 0)) {
                if (trail == null || mode == Mode.ARRIVED) {
                    reset();
                    trail = new DimensionalTrail(level.dimension(), player.position());
                    markTrailChanged();
                    message("marked");
                } else if (mode == Mode.RETURNING && trail.waitingToRejoin()) {
                    resumeRecording();
                } else {
                    message(mode == Mode.RETURNING ? "returning" : "recording");
                }
            }
            recordPressedAt = -1;
            holdHandled = false;
        }
    }

    private void toggleReturn() {
        if (mode == Mode.RETURNING) {
            if (trail.waitingToRejoin()) {
                rejoin();
            } else {
                resumeRecording();
            }
        } else if (trail != null && trail.hasRecordedPath()) {
            trail.beginReturn(level.dimension(), WaythreadConfig.MAX_RECORDED_POINTS.get());
            returnStartPointCount = trail.pointCount();
            mode = Mode.RETURNING;
            markTrailChanged();
            particles.clear();
            if (trail.waitingToRejoin()) {
                showRejoinHint();
            } else {
                message("returning");
            }
        } else {
            message("no_trail");
        }
    }

    private void rejoin() {
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
        ResourceKey<Level> destination = trail.boundaryDestination();
        if (destination != null && !destination.equals(level.dimension())) {
            message("return_dimension", dimensionName(destination));
        } else {
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
        return Component.literal(dimension.location().toString());
    }

    private void resumeRecording() {
        trail.resume(level.dimension(), player.position(), WaythreadConfig.RECORD_POINT_SPACING.get(),
                WaythreadConfig.MAX_RECORDED_POINTS.get(), WaythreadConfig.RETURN_ERASE_DISTANCE.get(),
                new WalkableTerrain(level)::canJoin);
        mode = Mode.RECORDING;
        returnStartPointCount = 0;
        markTrailChanged();
        clearDisplay();
        message("resumed");
    }

    private void finishReturn() {
        DimensionalTrail completed = trail;
        reset();
        returnCompletedUntil = player.tickCount + COMPLETED_PROGRESS_TICKS;
        if (!WaythreadConfig.CLEAR_ON_ARRIVAL.get()) {
            trail = completed;
            mode = Mode.ARRIVED;
        }
        markTrailChanged();
        saveTrail(true);
        if (WaythreadConfig.SHOW_ARRIVAL_MESSAGE.get()) {
            message("arrived");
            Minecraft.getInstance().getSoundManager().play(
                    SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0F, 0.5F));
        }
    }

    private void showTrail(BreadcrumbTrail path, List<Vec3> tail, Vec3 position, double rangeSquared, boolean waypoints) {
        particles.prune();
        TrailParticles.TrailDraw drawing = particles.startTrail(position, rangeSquared,
                WaythreadConfig.TRAIL_PARTICLE_SPACING.get(), WaythreadConfig.TRAIL_PARTICLE_LIMIT.get(), mode == Mode.RETURNING);
        drawing.path(tail, path.currentStartDistance(), 0);
        List<BreadcrumbTrail.Segment> segments = path.segments();
        for (int index = segments.size() - 1; index >= 0 && drawing.hasRoom(); index--) {
            BreadcrumbTrail.Segment segment = segments.get(index);
            List<Vec3> points = segment.samples();
            if (!waypoints) {
                if (!segment.isNear(position, rangeSquared)) {
                    continue;
                }
                List<Vec3> generated = localRoutes.get(segment);
                if (generated != null) {
                    points = generated;
                } else if (search == null) {
                    searchedSegment = segment;
                    search = new RouteSearch(new WalkableTerrain(level), segment);
                }
            }
            drawing.path(points, segment.startDistance(), segment.length());
        }
        Vec3 anchor = path.anchor();
        int markerDistance = anchorDisplayDistance(WaythreadConfig.ANCHOR_SEE_THROUGH.get());
        if (trail.markerFor(path) == DimensionalTrail.Marker.START
                && anchor.distanceToSqr(position) <= markerDistance * markerDistance) {
            particles.anchor(anchor);
        }
    }

    private void discardTrail() {
        boolean hadRoute = trail != null;
        reset();
        if (hadRoute) {
            markTrailChanged();
            saveTrail(true);
        }
    }

    private void reset() {
        trail = null;
        lastPosition = null;
        mode = Mode.RECORDING;
        returnStartPointCount = 0;
        returnCompletedUntil = 0;
        clearDisplay();
    }

    private void clearDisplay() {
        searchedSegment = null;
        search = null;
        localRoutes.clear();
        particles.clear();
    }

    private void message(String key, Object... args) {
        player.displayClientMessage(Component.translatable("message.waythread." + key, args), true);
    }

    enum Mode {
        RECORDING, RETURNING, ARRIVED
    }
}
