package dev.waythread;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.Util;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
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
import org.lwjgl.glfw.GLFW;

@Mod(value = "waythread", dist = Dist.CLIENT)
public final class Waythread {
    private static final String CATEGORY = "key.categories.waythread";
    private static final ResourceLocation RETURN_PROGRESS_LAYER = ResourceLocation.fromNamespaceAndPath("waythread", "return_progress");
    private static final int CACHE_MARGIN = 6;
    private static final long CLEAR_HOLD_MS = 2_000;
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
    private BreadcrumbTrail trail;
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
        container.registerConfig(ModConfig.Type.CLIENT, WaythreadConfig.SPEC);
        container.registerExtensionPoint(IConfigScreenFactory.class, (mod, parent) ->
                new ConfigurationScreen(mod, parent, TrailConfigurationScreen::new));
        modBus.addListener(this::registerKeys);
        modBus.addListener(this::registerGuiLayers);
        NeoForge.EVENT_BUS.addListener(this::tick);
        NeoForge.EVENT_BUS.addListener(this::renderAnchor);
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
        if ((!returning && player.tickCount >= returnCompletedUntil) || !WaythreadConfig.SHOW_RETURN_PROGRESS.get()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != player || minecraft.level != level || minecraft.options.hideGui) {
            return;
        }

        int percent = returning ? returnProgressPercent(returnStartPointCount, trail.pointCount()) : 100;
        int center = graphics.guiWidth() / 2;
        int y = graphics.guiHeight() - 82;
        int width = 88;
        int left = center - width / 2;
        graphics.fill(left, y, left + width, y + 1, 0xB019232B);
        int filled = width * percent / 100;
        if (filled > 0) {
            graphics.fill(left, y, left + filled, y + 1, 0xFF6BE4F4);
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
        if (minecraft.level != level || minecraft.player != player) {
            reset();
            level = minecraft.level;
            player = minecraft.player;
        }
        boolean canUseKeys = level != null && player != null && player.isAlive() && minecraft.screen == null;
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
        if (trail == null || player == null || !player.isAlive() || minecraft.isPaused()) {
            return;
        }

        Vec3 position = player.position();
        boolean returning = mode == Mode.RETURNING;
        boolean truncated = false;
        if (returning) {
            BreadcrumbTrail.RewindResult result = trail.rewind(position, WaythreadConfig.RETURN_ERASE_DISTANCE.get());
            if (result == BreadcrumbTrail.RewindResult.ARRIVED) {
                finishReturn();
                return;
            }
            trail.trackReturnMovement(position, WaythreadConfig.RECORD_POINT_SPACING.get(),
                    WaythreadConfig.MAX_RECORDED_POINTS.get());
            if (result == BreadcrumbTrail.RewindResult.TRUNCATED) {
                truncated = true;
                localRoutes.keySet().removeIf(segment -> !trail.contains(segment));
                particles.clear();
            }
        } else if (mode == Mode.RECORDING) {
            int pointLimit = WaythreadConfig.MAX_RECORDED_POINTS.get();
            if (trail.record(position, WaythreadConfig.RECORD_POINT_SPACING.get(), pointLimit)) {
                message("point_limit", pointLimit);
            }
        }
        if (!trailVisible) {
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
                    || !trail.contains(searchedSegment)) {
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
        boolean showDirection = returning || (mode == Mode.RECORDING && WaythreadConfig.SHOW_RECORDING_DIRECTION.get());
        if (!refresh && !showDirection) {
            return;
        }
        particles.updateColors(WaythreadConfig.TRAIL_COLOR.get(), WaythreadConfig.anchorColor());
        List<Vec3> tail = mode == Mode.RECORDING && !waypoints ? trail.recordingTail() : trail.current();
        if (refresh) {
            particles.updateAppearance(displayMode, WaythreadConfig.PARTICLE_STYLE.get(), WaythreadConfig.ANCHOR_APPEARANCE.get());
            if (!waypoints) {
                int cacheDistance = loadDistance + CACHE_MARGIN;
                localRoutes.keySet().removeIf(segment -> !segment.isNear(position, cacheDistance * cacheDistance));
            }
            showTrail(tail, position, rangeSquared, waypoints);
        }
        if (showDirection) {
            particles.pulse(trail, tail, localRoutes, position, rangeSquared, player.tickCount, returning);
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
        AnchorRenderer.Appearance appearance = WaythreadConfig.ANCHOR_APPEARANCE.get();
        boolean particleAnchor = appearance == AnchorRenderer.Appearance.PARTICLES;
        boolean seeThrough = WaythreadConfig.ANCHOR_SEE_THROUGH.get();
        if (particleAnchor && !seeThrough) {
            return;
        }
        Vec3 anchor = trail.anchor();
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
        } else {
            String color = WaythreadConfig.anchorColor();
            int rgb = Integer.parseInt(color, 1, color.length(), 16);
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
                reset();
                message("cleared");
            }
            holdHandled = true;
        }
        if (!record.isDown()) {
            if (!holdHandled && (clicked || recordPressedAt >= 0)) {
                if (trail == null || mode == Mode.ARRIVED) {
                    reset();
                    trail = new BreadcrumbTrail(player.position());
                    message("marked");
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
            trail.resume(player.position(), WaythreadConfig.RECORD_POINT_SPACING.get(),
                    WaythreadConfig.MAX_RECORDED_POINTS.get(), WaythreadConfig.RETURN_ERASE_DISTANCE.get(),
                    new WalkableTerrain(level)::canJoin);
            mode = Mode.RECORDING;
            returnStartPointCount = 0;
            particles.clear();
            message("resumed");
        } else if (trail != null && trail.hasRecordedPath()) {
            trail.beginReturn(WaythreadConfig.MAX_RECORDED_POINTS.get());
            returnStartPointCount = trail.pointCount();
            mode = Mode.RETURNING;
            particles.clear();
            message("returning");
        } else {
            message("no_trail");
        }
    }

    private void finishReturn() {
        BreadcrumbTrail completed = trail;
        reset();
        returnCompletedUntil = player.tickCount + COMPLETED_PROGRESS_TICKS;
        if (!WaythreadConfig.CLEAR_ON_ARRIVAL.get()) {
            trail = completed;
            mode = Mode.ARRIVED;
        }
        if (WaythreadConfig.SHOW_ARRIVAL_MESSAGE.get()) {
            message("arrived");
            Minecraft.getInstance().getSoundManager().play(
                    SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0F, 0.5F));
        }
    }

    private void showTrail(List<Vec3> tail, Vec3 position, double rangeSquared, boolean waypoints) {
        particles.prune();
        TrailParticles.TrailDraw drawing = particles.startTrail(position, rangeSquared,
                WaythreadConfig.TRAIL_PARTICLE_SPACING.get(), WaythreadConfig.TRAIL_PARTICLE_LIMIT.get(), mode == Mode.RETURNING);
        drawing.path(tail);
        List<BreadcrumbTrail.Segment> segments = trail.segments();
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
            drawing.path(points);
        }
        Vec3 anchor = trail.anchor();
        int markerDistance = anchorDisplayDistance(WaythreadConfig.ANCHOR_SEE_THROUGH.get());
        if (anchor.distanceToSqr(position) <= markerDistance * markerDistance) {
            particles.anchor(anchor);
        }
    }

    private void reset() {
        trail = null;
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

    private enum Mode {
        RECORDING, RETURNING, ARRIVED
    }
}
