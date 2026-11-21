package dev.waythread;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.SimpleAnimatedParticle;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import net.neoforged.neoforge.common.TranslatableEnum;
import org.joml.Vector3f;

final class TrailParticles {
    private static final double THREAD_HEIGHT = 0.15;
    private static final double PULSE_LENGTH = 8.0;
    private final List<TrackedParticle> visible = new ArrayList<>();
    private final List<Particle> anchorParticles = new ArrayList<>();
    private int tick;
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
            threadDust = new DustParticleOptions(trailRgb, 0.72F);
            pulseColor = new Vector3f(tint).lerp(new Vector3f(1, 1, 1), 0.55F);
        }
        if (!anchorColor.equals(this.anchorColor)) {
            this.anchorColor = anchorColor;
            anchorRgb = Integer.parseInt(anchorColor, 1, anchorColor.length(), 16);
            anchorDust = new DustParticleOptions(anchorRgb, 1.3F);
        }
    }

    void setTick(int tick) {
        this.tick = tick;
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
        private static final double DISTANCE_EPSILON = 1.0e-8;
        private final Vec3 player;
        private final double rangeSquared;
        private final double spacing;
        private final int limit;
        private final DisplayMode mode;
        private final int gridScale;
        private final Consumer<Vec3> spawn;
        private final Set<BlockPos> drawn = new HashSet<>();

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

        void path(List<Vec3> points, double startDistance, double recordedLength) {
            if (points.isEmpty() || !hasRoom()) {
                return;
            }
            if (mode == DisplayMode.WAYPOINTS) {
                for (int i = points.size() - 1; i >= 0 && hasRoom(); i--) {
                    draw(points.get(i));
                }
                return;
            }

            double displayLength = BreadcrumbTrail.pathLength(points);
            double lineLength = recordedLength > 0 ? recordedLength : displayLength;
            double endDistance = startDistance + lineLength;
            double sampleDistance = Math.floor((endDistance + DISTANCE_EPSILON) / spacing) * spacing;
            if (sampleDistance < startDistance - DISTANCE_EPSILON) {
                return;
            }
            Vec3 end = points.getLast();
            if (lineLength == 0 || displayLength == 0) {
                draw(end);
                return;
            }
            // A* may change the displayed line length. Keep the sampling phase on the recorded route.
            double distanceScale = displayLength / lineLength;
            double step = spacing * distanceScale;
            double distanceToNext = Math.max(0, endDistance - sampleDistance) * distanceScale;
            for (int i = points.size() - 2; i >= 0 && hasRoom(); i--) {
                Vec3 start = points.get(i);
                double length = end.distanceTo(start);
                if (length > 0) {
                    while (distanceToNext <= length + DISTANCE_EPSILON && hasRoom()) {
                        draw(end.lerp(start, Math.min(distanceToNext / length, 1.0)));
                        distanceToNext += step;
                    }
                    distanceToNext -= length;
                }
                end = start;
            }
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

    AnchorQuads extractSeeThroughAnchor(Camera camera, float partialTick, Vec3 displayOffset) {
        AnchorQuads quads = new AnchorQuads(displayOffset);
        for (Particle particle : anchorParticles) {
            if (particle.isAlive() && particle instanceof SingleQuadParticle quad) {
                quad.extract(quads, camera, partialTick);
            }
        }
        return quads;
    }

    static final class AnchorQuads extends QuadParticleRenderState {
        private final Vec3 offset;
        private final List<Quad> quads = new ArrayList<>();

        AnchorQuads(Vec3 offset) {
            this.offset = offset;
        }

        @Override
        public void add(SingleQuadParticle.Layer layer, float x, float y, float z, float xRot, float yRot,
                float zRot, float wRot, float scale, float u0, float u1, float v0, float v1, int color, int light) {
            quads.add(new Quad(x + (float) offset.x, y + (float) offset.y, z + (float) offset.z,
                    xRot, yRot, zRot, wRot, scale, u0, u1, v0, v1, color, light));
        }

        @Override
        public boolean isEmpty() {
            return quads.isEmpty();
        }

        void submit(SubmitCustomGeometryEvent event) {
            RenderType type = offset.lengthSqr() > 0 ? AnchorParticleRenderTypes.PROJECTED
                    : AnchorParticleRenderTypes.OCCLUDED;
            event.getSubmitNodeCollector().submitCustomGeometry(event.getPoseStack(), type,
                    (pose, buffer) -> renderQuads(buffer));
        }

        private void renderQuads(VertexConsumer buffer) {
            for (Quad quad : quads) {
                renderRotatedQuad(buffer, quad.x, quad.y, quad.z, quad.xRot, quad.yRot, quad.zRot, quad.wRot,
                        quad.scale, quad.u0, quad.u1, quad.v0, quad.v1, quad.color, quad.light);
            }
        }

        private record Quad(float x, float y, float z, float xRot, float yRot, float zRot, float wRot,
                            float scale, float u0, float u1, float v0, float v1, int color, int light) {
        }
    }

    void pulse(BreadcrumbTrail trail, List<Vec3> tail, Map<BreadcrumbTrail.Segment, List<Vec3>> routes,
            Vec3 player, double rangeSquared, int tick, boolean towardStart) {
        List<Vec3> recentPath = returnPath(trail, tail, routes);
        Vec3 point = pulsePosition(towardStart ? recentPath : recentPath.reversed(), tick);
        if (point != null && point.distanceToSqr(player) <= rangeSquared) {
            highlight(point);
        }
    }

    void highlight(Vec3 point) {
        Particle glow = spawn(ParticleTypes.END_ROD, point.add(0, THREAD_HEIGHT + 0.07, 0), 4);
        if (glow instanceof SingleQuadParticle quad) {
            quad.setColor(pulseColor.x, pulseColor.y, pulseColor.z);
            quad.scale(0.75F);
        }
    }

    static Vec3 pulsePosition(List<Vec3> path, int tick) {
        double length = BreadcrumbTrail.pathLength(path);
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
        if (effect instanceof SingleQuadParticle quad && style != Style.DUST) {
            quad.setColor((rgb >> 16) / 255.0F, ((rgb >> 8) & 255) / 255.0F, (rgb & 255) / 255.0F);
            quad.scale(style.scale * scale);
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
            // A full vanilla queue can evict a particle without marking it dead.
            // Allow for the tick that moves the new particle into its render group.
            visible.add(new TrackedParticle(effect, tick + lifetime + 2));
        }
        return effect;
    }

    void prune() {
        visible.removeIf(effect -> effect.expire(tick));
        anchorParticles.removeIf(effect -> !effect.isAlive());
    }

    void clear() {
        visible.forEach(effect -> effect.particle().remove());
        visible.clear();
        anchorParticles.clear();
    }

    record TrackedParticle(Particle particle, int expiryTick) {
        boolean expire(int tick) {
            if (tick - expiryTick >= 0) {
                particle.remove();
                return true;
            }
            return !particle.isAlive();
        }
    }

    static void registerPipelines(RegisterRenderPipelinesEvent event) {
        event.registerPipeline(AnchorParticleRenderTypes.OCCLUDED_PIPELINE);
        event.registerPipeline(AnchorParticleRenderTypes.PROJECTED_PIPELINE);
    }

    private static final class AnchorParticleRenderTypes {
        private static final RenderPipeline OCCLUDED_PIPELINE = pipeline("anchor_particles_behind_blocks", CompareOp.GREATER_THAN);
        private static final RenderPipeline PROJECTED_PIPELINE = pipeline("anchor_particles_at_distance", CompareOp.ALWAYS_PASS);
        private static final RenderType OCCLUDED = type("anchor_particles_behind_blocks", OCCLUDED_PIPELINE);
        private static final RenderType PROJECTED = type("anchor_particles_at_distance", PROJECTED_PIPELINE);

        private static RenderPipeline pipeline(String name, CompareOp depthTest) {
            return RenderPipeline.builder(RenderPipelines.PARTICLE_SNIPPET)
                    .withLocation(Identifier.fromNamespaceAndPath(Waythread.MOD_ID, "pipeline/" + name))
                    .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
                    .withDepthStencilState(new DepthStencilState(depthTest, false))
                    .withCull(false)
                    .build();
        }

        private static RenderType type(String name, RenderPipeline pipeline) {
            return RenderType.create(name, RenderSetup.builder(pipeline)
                    .withTexture("Sampler0", SingleQuadParticle.Layer.TRANSLUCENT.textureAtlasLocation())
                    .useLightmap()
                    .sortOnUpload()
                    .createRenderSetup());
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
