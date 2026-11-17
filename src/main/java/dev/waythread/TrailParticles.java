package dev.waythread;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.SimpleAnimatedParticle;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.TranslatableEnum;
import org.joml.Vector3f;

final class TrailParticles {
    private static final double THREAD_HEIGHT = 0.15;
    private static final double PULSE_LENGTH = 8.0;
    private final List<Particle> visible = new ArrayList<>();
    private DisplayMode displayMode = DisplayMode.LINE;
    private Style style = Style.DUST;
    private Density density = Density.STANDARD;
    private String trailColor;
    private String anchorColor;
    private int trailRgb;
    private int anchorRgb;
    private DustParticleOptions threadDust;
    private DustParticleOptions anchorDust;
    private Vector3f pulseColor;

    void updateAppearance(DisplayMode displayMode, Style style, Density density) {
        if (displayMode != this.displayMode || style != this.style || density != this.density) {
            clear();
            this.displayMode = displayMode;
            this.style = style;
            this.density = density;
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

    void thread(List<Vec3> points, Vec3 player, double rangeSquared, int limit,
            Set<BlockPos> drawn, boolean returning) {
        ParticleOptions type = style.options(threadDust);
        int lifetime = returning ? 7 : 11;
        for (int i = points.size() - 1; i >= 0 && drawn.size() < limit; i--) {
            Vec3 point = points.get(i);
            dot(type, point, player, rangeSquared, drawn, lifetime);
            if (i > 0 && displayMode == DisplayMode.LINE) {
                Vec3 previous = points.get(i - 1);
                int steps = (int) Math.ceil(point.distanceTo(previous) / density.spacing);
                for (int step = 1; step < steps && drawn.size() < limit; step++) {
                    dot(type, point.lerp(previous, (double) step / steps), player, rangeSquared, drawn, lifetime);
                }
            }
        }
    }

    private void dot(ParticleOptions type, Vec3 point, Vec3 player, double rangeSquared, Set<BlockPos> drawn, int lifetime) {
        if (point.distanceToSqr(player) <= rangeSquared
                && drawn.add(BlockPos.containing(point.x * density.gridScale, point.y * density.gridScale,
                        point.z * density.gridScale))) {
            spawnStyled(type, point.add(0, THREAD_HEIGHT, 0), lifetime, trailRgb, 1.0F);
        }
    }

    void anchor(Vec3 point) {
        ParticleOptions type = style.options(anchorDust);
        spawnStyled(type, point.add(0, 0.24, 0), 11, anchorRgb, 1.8F);
        spawnStyled(type, point.add(0, 0.60, 0), 11, anchorRgb, 1.8F);
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

    private void spawnStyled(ParticleOptions type, Vec3 point, int lifetime, int rgb, float scale) {
        Particle effect = spawn(type, point, lifetime);
        if (effect != null && style != Style.DUST) {
            effect.setColor((rgb >> 16) / 255.0F, ((rgb >> 8) & 255) / 255.0F, (rgb & 255) / 255.0F);
            effect.scale(style.scale * scale);
            if (effect instanceof SimpleAnimatedParticle animated) {
                animated.setFadeColor(rgb);
            }
        }
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
    }

    void clear() {
        visible.forEach(Particle::remove);
        visible.clear();
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

    enum Density implements TranslatableEnum {
        LOW(0.50, 3, 0.5), STANDARD(0.25, 5, 1.0), HIGH(0.15, 8, 1.6);

        private final double spacing;
        private final int gridScale;
        private final double budgetScale;

        Density(double spacing, int gridScale, double budgetScale) {
            this.spacing = spacing;
            this.gridScale = gridScale;
            this.budgetScale = budgetScale;
        }

        int particleLimit(int loadDistance) {
            return (int) Math.round(Math.max(128, loadDistance * 9) * budgetScale);
        }

        @Override
        public Component getTranslatedName() {
            return Component.translatable("waythread.configuration.particleDensity." + name().toLowerCase(Locale.ROOT));
        }
    }
}
