package dev.waythread;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.Util;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

@Mod(value = "waythread", dist = Dist.CLIENT)
public final class Waythread {
    private static final String CATEGORY = "key.categories.waythread";
    private static final int CACHE_MARGIN = 6;
    private static final long CLEAR_HOLD_MS = 2_000;

    private final KeyMapping record = new KeyMapping("key.waythread.navigate",
            KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_J, CATEGORY);
    private final KeyMapping switchMode = new KeyMapping("key.waythread.switch_mode",
            KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, CATEGORY);
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

    public Waythread(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, WaythreadConfig.SPEC);
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        modBus.addListener(this::registerKeys);
        NeoForge.EVENT_BUS.addListener(this::tick);
    }

    private void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(record);
        event.register(switchMode);
    }

    private void tick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != level || minecraft.player != player) {
            reset();
            level = minecraft.level;
            player = minecraft.player;
        }
        boolean canUseKeys = level != null && player != null && player.isAlive() && minecraft.screen == null;
        handleRecordKey(canUseKeys);
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
        int loadDistance = WaythreadConfig.LOAD_DISTANCE.get();
        double rangeSquared = loadDistance * loadDistance;
        if (mode == Mode.RETURNING) {
            if (trail.rewind(position, WaythreadConfig.RETURN_ERASE_DISTANCE.get())) {
                finishReturn();
                return;
            }
        } else if (mode == Mode.RECORDING) {
            trail.record(position, WaythreadConfig.RECORD_POINT_SPACING.get());
        }
        if (search != null) {
            if (!searchedSegment.isNear(position, rangeSquared)
                    || (mode == Mode.RETURNING && !trail.segments().contains(searchedSegment))) {
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
        if (player.tickCount % 5 == 0) {
            int cacheDistance = loadDistance + CACHE_MARGIN;
            localRoutes.keySet().removeIf(segment -> !segment.isNear(position, cacheDistance * cacheDistance));
            showTrail(position, rangeSquared, Math.max(128, loadDistance * 9));
        }
        boolean returning = mode == Mode.RETURNING;
        if (returning || (mode == Mode.RECORDING && WaythreadConfig.SHOW_RECORDING_DIRECTION.get())) {
            particles.pulse(trail, localRoutes, position, rangeSquared, player.tickCount, returning);
        }
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
            trail.resume(player.position());
            mode = Mode.RECORDING;
            particles.clear();
            message("resumed");
        } else if (trail != null && trail.hasRecordedPath()) {
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

    private void showTrail(Vec3 position, double rangeSquared, int particleLimit) {
        particles.prune();
        boolean returning = mode == Mode.RETURNING;
        Set<BlockPos> drawn = new HashSet<>();
        particles.thread(trail.current(), position, rangeSquared, particleLimit, drawn, returning);
        List<BreadcrumbTrail.Segment> segments = trail.segments();
        for (int index = segments.size() - 1; index >= 0 && drawn.size() < particleLimit; index--) {
            BreadcrumbTrail.Segment segment = segments.get(index);
            if (!segment.isNear(position, rangeSquared)) {
                continue;
            }
            List<Vec3> generated = localRoutes.get(segment);
            if (generated == null && search == null) {
                searchedSegment = segment;
                search = new RouteSearch(new WalkableTerrain(level), segment);
            }
            particles.thread(generated == null ? segment.samples() : generated,
                    position, rangeSquared, particleLimit, drawn, returning);
        }
        Vec3 anchor = trail.anchor();
        if (anchor.distanceToSqr(position) <= rangeSquared) {
            particles.anchor(anchor);
        }
    }

    private void reset() {
        trail = null;
        mode = Mode.RECORDING;
        searchedSegment = null;
        search = null;
        localRoutes.clear();
        particles.clear();
    }

    private void message(String key) {
        player.displayClientMessage(Component.translatable("message.waythread." + key), true);
    }

    private enum Mode {
        RECORDING, RETURNING, ARRIVED
    }
}
