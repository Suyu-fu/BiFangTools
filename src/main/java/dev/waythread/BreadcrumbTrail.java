package dev.waythread;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.Vec3;

final class BreadcrumbTrail {
    private static final double SEGMENT_LENGTH = 4.5;
    private static final double MAX_TICK_MOVEMENT_SQUARED = 8 * 8;

    private final Vec3 anchor;
    private final List<Segment> segments = new ArrayList<>();
    private final List<Vec3> current = new ArrayList<>();
    private Vec3 lastPosition;
    private double distanceSinceSample;
    private double segmentLength;

    BreadcrumbTrail(Vec3 anchor) {
        this.anchor = anchor;
        lastPosition = anchor;
        current.add(anchor);
    }

    void record(Vec3 position, double spacing) {
        double movementSquared = position.distanceToSqr(lastPosition);
        lastPosition = position;

        // Keep the previous segment, but don't connect across teleports.
        if (movementSquared > MAX_TICK_MOVEMENT_SQUARED) {
            finishSegment();
            current.set(0, position);
            distanceSinceSample = 0;
            return;
        }

        distanceSinceSample += Math.sqrt(movementSquared);
        if (movementSquared == 0 || distanceSinceSample < spacing) {
            return;
        }

        Vec3 lastSample = current.getLast();
        if (current.size() >= 2) {
            Vec3 before = lastSample.subtract(current.get(current.size() - 2)).normalize();
            Vec3 after = position.subtract(lastSample).normalize();
            if (before.dot(after) < 0.35) {
                finishSegment();
            }
        }

        current.add(position);
        segmentLength += distanceSinceSample;
        distanceSinceSample = 0;
        if (segmentLength >= SEGMENT_LENGTH) {
            finishSegment();
        }
    }

    boolean hasRecordedPath() {
        return current.size() > 1 || !segments.isEmpty();
    }

    boolean rewind(Vec3 position, double eraseDistance) {
        double reachSquared = eraseDistance * eraseDistance;
        while (!current.isEmpty() || !segments.isEmpty()) {
            if (current.isEmpty()) {
                current.addAll(segments.removeLast().samples());
            }
            if (position.distanceToSqr(current.getLast()) > reachSquared) {
                return false;
            }
            current.removeLast();
        }
        return position.distanceToSqr(anchor) <= reachSquared;
    }

    void resume(Vec3 position) {
        if (current.size() > 1) {
            finishSegment();
        }
        current.clear();
        current.add(position);
        lastPosition = position;
        distanceSinceSample = 0;
        segmentLength = 0;
    }

    private void finishSegment() {
        if (current.size() > 1) {
            segments.add(new Segment(List.copyOf(current)));
        }
        Vec3 end = current.getLast();
        current.clear();
        current.add(end);
        segmentLength = 0;
    }

    Vec3 anchor() {
        return anchor;
    }

    List<Segment> segments() {
        return segments;
    }

    List<Vec3> current() {
        return current;
    }

    record Segment(List<Vec3> samples) {
        Vec3 start() {
            return samples.getFirst();
        }

        Vec3 end() {
            return samples.getLast();
        }

        boolean isNear(Vec3 position, double rangeSquared) {
            for (Vec3 sample : samples) {
                if (sample.distanceToSqr(position) <= rangeSquared) {
                    return true;
                }
            }
            return false;
        }
    }
}
