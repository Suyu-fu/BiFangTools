package dev.waythread;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.SimpleAnimatedParticle;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.TranslatableEnum;
import org.joml.Matrix4fStack;
import org.joml.Vector3f;

final class TrailParticles {
    private static final double THREAD_HEIGHT = 0.15;
    private static final double PULSE_LENGTH = 8.0;
    private final List<Particle> visible = new ArrayList<>();
    private final List<Particle> anchorParticles = new ArrayList<>();
    private DisplayMode displayMode = DisplayMode.LINE;
    private Style style = Style.DUST;
    private AnchorRenderer.Appearance anchorAppearance = AnchorRenderer.Appearance.PARTICLES;
    private String trailColor;
    private String anchorColor;
    private int trailRgb;
    private int anchorRgb;
    private DustParticleOptions threadDust;
    private DustParticleOptions anchorDust;
    private Vector3f pulseColor;

    void updateAppearance(DisplayMode displayMode, Style style, AnchorRenderer.Appearance anchorAppearance) {
        if (displayMode != this.displayMode || style != this.style || anchorAppearance != this.anchorAppearance) {
            clear();
            this.displayMode = displayMode;
            this.style = style;
            this.anchorAppearance = anchorAppearance;
        }
    }

    void updateColors(String trailColor, String anchorColor) {
        if (!trailColor.equals(this.trailColor)) {
            this.trailColor = trailColor;
            trailRgb = Integer.parseInt(trailColor, 1, trailColor.length(), 16);
            Vector3f tint = colorVector(trailRgb);
            threadDust = new DustParticleOptions(tint, 0.72F);
            pulseColor = new Vector3f(tint).lerp(new Vector3f(1, 1, 1), 0.55F);
        }
        if (!anchorColor.equals(this.anchorColor)) {
            this.anchorColor = anchorColor;
            anchorRgb = Integer.parseInt(anchorColor, 1, anchorColor.length(), 16);
            anchorDust = new DustParticleOptions(colorVector(anchorRgb), 1.3F);
        }
    }

    private static Vector3f colorVector(int rgb) {
        return new Vector3f((rgb >> 16) / 255.0F, ((rgb >> 8) & 255) / 255.0F, (rgb & 255) / 255.0F);
    }

    TrailDraw startTrail(Vec3 player, double rangeSquared, double spacing, int limit, boolean returning) {
        ParticleOptions type = style.options(threadDust);
        int lifetime = returning ? 7 : 11;
        return new TrailDraw(player, rangeSquared, spacing, limit, displayMode,
                point -> spawnStyled(type, point.add(0, THREAD_HEIGHT, 0), lifetime, trailRgb, 1.0F));
    }

    static final class TrailDraw {
        private final Vec3 player;
        private final double rangeSquared;
        private final double spacing;
        private final int limit;
        private final DisplayMode mode;
        private final int gridScale;
        private final Consumer<Vec3> spawn;
        private final Set<BlockPos> drawn = new HashSet<>();
        private Vec3 previousStart;
        private double distanceToNext;

        TrailDraw(Vec3 player, double rangeSquared, double spacing, int limit, DisplayMode mode, Consumer<Vec3> spawn) {
            this.player = player;
            this.rangeSquared = rangeSquared;
            this.spacing = spacing;
            this.limit = limit;
            this.mode = mode;
            this.gridScale = mode == DisplayMode.WAYPOINTS ? 3 : 32;
            this.spawn = spawn;
        }

        boolean hasRoom() {
            return limit == 0 || drawn.size() < limit;
        }

        void path(List<Vec3> points) {
            if (points.isEmpty() || !hasRoom()) {
                return;
            }
            if (mode == DisplayMode.WAYPOINTS) {
                for (int i = points.size() - 1; i >= 0 && hasRoom(); i--) {
                    draw(points.get(i));
                }
                return;
            }

            Vec3 end = points.getLast();
            if (!end.equals(previousStart)) {
                draw(end);
                distanceToNext = spacing;
            }
            for (int i = points.size() - 2; i >= 0 && hasRoom(); i--) {
                Vec3 start = points.get(i);
                double length = end.distanceTo(start);
                if (length > 0) {
                    while (distanceToNext <= length && hasRoom()) {
                        draw(end.lerp(start, distanceToNext / length));
                        distanceToNext += spacing;
                    }
                    distanceToNext -= length;
                }
                end = start;
            }
            previousStart = end;
        }

        private void draw(Vec3 point) {
            if (point.distanceToSqr(player) <= rangeSquared
                    && drawn.add(BlockPos.containing(point.x * gridScale, point.y * gridScale, point.z * gridScale))) {
                spawn.accept(point);
            }
        }
    }

    void anchor(Vec3 point) {
        if (anchorAppearance != AnchorRenderer.Appearance.PARTICLES) {
            return;
        }
        ParticleOptions type = style.options(anchorDust);
        spawnAnchorParticle(type, point.add(0, 0.24, 0));
        spawnAnchorParticle(type, point.add(0, 0.60, 0));
    }

    private void spawnAnchorParticle(ParticleOptions type, Vec3 point) {
        Particle particle = spawnStyled(type, point, 11, anchorRgb, 1.8F);
        if (particle != null) {
            anchorParticles.add(particle);
        }
    }

    void renderSeeThroughAnchor(RenderLevelStageEvent event, float partialTick, Vec3 displayOffset) {
        BufferBuilder buffer = null;
        for (Particle particle : anchorParticles) {
            if (particle.isAlive()) {
                if (buffer == null) {
                    buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE);
                }
                particle.render(buffer, event.getCamera(), partialTick);
            }
        }
        MeshData mesh = buffer == null ? null : buffer.build();
        if (mesh == null) {
            return;
        }
        Matrix4fStack view = RenderSystem.getModelViewStack();
        view.pushMatrix().mul(event.getModelViewMatrix());
        boolean projected = displayOffset.lengthSqr() > 0;
        if (projected) {
            view.translate((float) displayOffset.x, (float) displayOffset.y, (float) displayOffset.z);
        }
        RenderSystem.applyModelViewMatrix();
        try {
            if (projected) {
                RenderSystem.disableDepthTest();
            }
            (projected ? AnchorParticleRenderTypes.PROJECTED : AnchorParticleRenderTypes.OCCLUDED).draw(mesh);
        } finally {
            view.popMatrix();
            RenderSystem.applyModelViewMatrix();
        }
    }

    void pulse(BreadcrumbTrail trail, List<Vec3> tail, Map<BreadcrumbTrail.Segment, List<Vec3>> routes,
            Vec3 player, double rangeSquared, int tick, boolean towardStart) {
        List<Vec3> recentPath = returnPath(trail, tail, routes);
        Vec3 point = pulsePosition(towardStart ? recentPath : recentPath.reversed(), tick);
        if (point != null && point.distanceToSqr(player) <= rangeSquared) {
            Particle glow = spawn(ParticleTypes.END_ROD, point.add(0, THREAD_HEIGHT + 0.07, 0), 4);
            if (glow != null) {
                glow.setColor(pulseColor.x, pulseColor.y, pulseColor.z);
                glow.scale(0.75F);
            }
        }
    }

    static Vec3 pulsePosition(List<Vec3> path, int tick) {
        double length = 0;
        for (int i = 1; i < path.size(); i++) {
            length += path.get(i - 1).distanceTo(path.get(i));
        }
        if (length < 0.2) {
            return null;
        }
        // Let the last highlight fade before starting another pulse.
        double offset = (tick * 0.18) % (length + 0.7);
        for (int i = 1; i < path.size(); i++) {
            Vec3 from = path.get(i - 1);
            Vec3 to = path.get(i);
            double link = from.distanceTo(to);
            if (offset <= link) {
                return from.lerp(to, offset / link);
            }
            offset -= link;
        }
        return null;
    }

    static List<Vec3> returnPath(BreadcrumbTrail trail, List<Vec3> tail,
            Map<BreadcrumbTrail.Segment, List<Vec3>> routes) {
        List<Vec3> path = new ArrayList<>();
        List<Vec3> points = tail;
        int previousSegment = trail.segments().size() - 1;
        double length = 0;
        while (!points.isEmpty()) {
            for (int i = points.size() - 1; i >= 0; i--) {
                Vec3 point = points.get(i);
                if (!path.isEmpty()) {
                    Vec3 last = path.getLast();
                    double link = last.distanceTo(point);
                    if (link < 1.0e-5) {
                        continue;
                    }
                    if (length + link >= PULSE_LENGTH) {
                        path.add(last.lerp(point, (PULSE_LENGTH - length) / link));
                        return path;
                    }
                    length += link;
                }
                path.add(point);
            }
            if (previousSegment < 0) {
                break;
            }
            BreadcrumbTrail.Segment segment = trail.segments().get(previousSegment--);
            if (!segment.end().equals(points.getFirst())) {
                break;
            }
            points = routes.getOrDefault(segment, segment.samples());
        }
        return path;
    }

    private Particle spawnStyled(ParticleOptions type, Vec3 point, int lifetime, int rgb, float scale) {
        Particle effect = spawn(type, point, lifetime);
        if (effect != null && style != Style.DUST) {
            effect.setColor((rgb >> 16) / 255.0F, ((rgb >> 8) & 255) / 255.0F, (rgb & 255) / 255.0F);
            effect.scale(style.scale * scale);
            if (effect instanceof SimpleAnimatedParticle animated) {
                animated.setFadeColor(rgb);
            }
        }
        return effect;
    }

    private Particle spawn(ParticleOptions type, Vec3 point, int lifetime) {
        Particle effect = Minecraft.getInstance().particleEngine.createParticle(
                type, point.x, point.y, point.z, 0, 0, 0);
        if (effect != null) {
            effect.setParticleSpeed(0, 0, 0);
            effect.setLifetime(lifetime);
            visible.add(effect);
        }
        return effect;
    }

    void prune() {
        visible.removeIf(effect -> !effect.isAlive());
        anchorParticles.removeIf(effect -> !effect.isAlive());
    }

    void clear() {
        visible.forEach(Particle::remove);
        visible.clear();
        anchorParticles.clear();
    }

    private static final class AnchorParticleRenderTypes {
        private static final RenderType OCCLUDED = create("waythread_anchor_particles_behind_blocks",
                RenderStateShard.GREATER_DEPTH_TEST);
        private static final RenderType PROJECTED = create("waythread_anchor_particles_at_distance",
                RenderStateShard.NO_DEPTH_TEST);

        @SuppressWarnings("deprecation") // ParticleEngine still registers this atlas in Minecraft 1.21.1.
        private static RenderType create(String name, RenderStateShard.DepthTestStateShard depthTest) {
            return RenderType.create(name, DefaultVertexFormat.PARTICLE, VertexFormat.Mode.QUADS, 1536,
                    RenderType.CompositeState.builder()
                            .setShaderState(new RenderStateShard.ShaderStateShard(GameRenderer::getParticleShader))
                            .setTextureState(new RenderStateShard.TextureStateShard(TextureAtlas.LOCATION_PARTICLES, false, false))
                            .setLightmapState(RenderStateShard.LIGHTMAP)
                            .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                            .setDepthTestState(depthTest)
                            .setCullState(RenderStateShard.NO_CULL)
                            .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                            .createCompositeState(false));
        }
    }

    enum Style implements TranslatableEnum {
        DUST(1.0F), SPARK(0.62F), GLOW(0.90F), MIST(0.45F);

        private final float scale;

        Style(float scale) {
            this.scale = scale;
        }

        ParticleOptions options(DustParticleOptions dust) {
            return switch (this) {
                case DUST -> dust;
                case SPARK -> ParticleTypes.ELECTRIC_SPARK;
                case GLOW -> ParticleTypes.END_ROD;
                case MIST -> ParticleTypes.WHITE_SMOKE;
            };
        }

        @Override
        public Component getTranslatedName() {
            return Component.translatable("waythread.configuration.particleStyle." + name().toLowerCase(Locale.ROOT));
        }
    }

    enum DisplayMode implements TranslatableEnum {
        LINE, WAYPOINTS;

        @Override
        public Component getTranslatedName() {
            return Component.translatable("waythread.configuration.displayMode." + name().toLowerCase(Locale.ROOT));
        }
    }

}
