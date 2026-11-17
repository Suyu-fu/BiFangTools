package dev.waythread;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

final class TrailParticles {
    private static final double THREAD_HEIGHT = 0.15;
    private static final double DOT_SPACING = 0.25;
    private static final double MAX_LINK_LENGTH = 2.5;
    private static final double PULSE_LENGTH = 8.0;
    private static final DustParticleOptions THREAD =
            new DustParticleOptions(new Vector3f(0.22F, 0.89F, 1.0F), 0.72F);
    private static final DustParticleOptions ANCHOR =
            new DustParticleOptions(new Vector3f(1.0F, 0.69F, 0.21F), 1.3F);

    private final List<Particle> visible = new ArrayList<>();

    void thread(List<Vec3> points, Vec3 player, double rangeSquared, int limit,
            Set<BlockPos> drawn, boolean returning) {
        int lifetime = returning ? 7 : 11;
        for (int i = points.size() - 1; i >= 0 && drawn.size() < limit; i--) {
            Vec3 point = points.get(i);
            dot(point, player, rangeSquared, drawn, lifetime);
            if (i > 0) {
                Vec3 previous = points.get(i - 1);
                double distance = point.distanceTo(previous);
                if (distance <= MAX_LINK_LENGTH) {
                    int steps = (int) Math.ceil(distance / DOT_SPACING);
                    for (int step = 1; step < steps && drawn.size() < limit; step++) {
                        dot(point.lerp(previous, (double) step / steps), player, rangeSquared, drawn, lifetime);
                    }
                }
            }
        }
    }

    private void dot(Vec3 point, Vec3 player, double rangeSquared, Set<BlockPos> drawn, int lifetime) {
        if (point.distanceToSqr(player) <= rangeSquared && drawn.add(BlockPos.containing(point.scale(5)))) {
            spawn(THREAD, point.add(0, THREAD_HEIGHT, 0), lifetime);
        }
    }

    void anchor(Vec3 point) {
        spawn(ANCHOR, point.add(0, 0.24, 0), 11);
        spawn(ANCHOR, point.add(0, 0.60, 0), 11);
    }

    void pulse(BreadcrumbTrail trail, Map<BreadcrumbTrail.Segment, List<Vec3>> routes,
            Vec3 player, double rangeSquared, int tick, boolean towardStart) {
        List<Vec3> recentPath = returnPath(trail, routes);
        Vec3 point = pulsePosition(towardStart ? recentPath : recentPath.reversed(), tick);
        if (point != null && point.distanceToSqr(player) <= rangeSquared) {
            Particle glow = spawn(ParticleTypes.END_ROD, point.add(0, THREAD_HEIGHT + 0.07, 0), 4);
            if (glow != null) {
                glow.setColor(0.65F, 1.0F, 1.0F);
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

    static List<Vec3> returnPath(BreadcrumbTrail trail, Map<BreadcrumbTrail.Segment, List<Vec3>> routes) {
        List<Vec3> path = new ArrayList<>();
        List<Vec3> points = trail.current();
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
                    if (link > MAX_LINK_LENGTH) {
                        return path;
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
}
