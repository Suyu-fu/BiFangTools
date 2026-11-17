package dev.waythread;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.Vec3;

final class BreadcrumbTrail {
    private static final double SEGMENT_LENGTH = 4.5;
    private static final double MAX_TICK_MOVEMENT_SQUARED = 8 * 8;
    private static final double TURN_TOLERANCE_SQUARED = 0.3 * 0.3;
    private static final double TURN_SAMPLE_SPACING_SQUARED = 0.15 * 0.15;

    private final Vec3 anchor;
    private final List<Segment> segments = new ArrayList<>();
    private final List<Vec3> current = new ArrayList<>();
    private final List<Vec3> pending = new ArrayList<>();
    private int currentStartIndex;
    private Vec3 lastPosition;
    private double distanceSinceSample;
    private double segmentLength;
    private Vec3 lastRewindPosition;
    private double lastEraseDistance;
    private boolean recordingPaused;

    BreadcrumbTrail(Vec3 anchor) {
        this.anchor = anchor;
        lastPosition = anchor;
        current.add(anchor);
    }

    boolean record(Vec3 position, double spacing, int pointLimit) {
        if (pointCount() >= pointLimit) {
            return pauseRecording();
        }
        if (recordingPaused) {
            recordingPaused = false;
            finishSegment();
            startAt(position);
            return pointCount() >= pointLimit && pauseRecording();
        }

        Vec3 previous = lastPosition;
        double movementSquared = position.distanceToSqr(previous);
        lastPosition = position;
        if (movementSquared == 0) {
            return false;
        }

        // Keep the previous segment, but don't connect across teleports.
        if (movementSquared > MAX_TICK_MOVEMENT_SQUARED) {
            appendSample(previous);
            finishSegment();
            if (pointCount() < pointLimit) {
                startAt(position);
            }
            return pointCount() >= pointLimit && pauseRecording();
        }

        Vec3 lastSample = current.getLast();
        if (distanceToLinkSquared(previous, lastSample, position) > TURN_TOLERANCE_SQUARED
                || crossedTurn(lastSample, position)) {
            appendSample(previous);
            finishSegment();
            if (pointCount() >= pointLimit) {
                return pauseRecording();
            }
        }
        distanceSinceSample += Math.sqrt(movementSquared);
        if (distanceSinceSample >= spacing) {
            appendSample(position);
            if (segmentLength >= SEGMENT_LENGTH) {
                finishSegment();
            }
        } else {
            Vec3 lastPending = pending.isEmpty() ? current.getLast() : pending.getLast();
            if (position.distanceToSqr(lastPending) >= TURN_SAMPLE_SPACING_SQUARED) {
                pending.add(position);
            }
        }
        return pointCount() >= pointLimit && pauseRecording();
    }

    private boolean pauseRecording() {
        if (recordingPaused) {
            return false;
        }
        recordingPaused = true;
        lastPosition = current.getLast();
        distanceSinceSample = 0;
        pending.clear();
        return true;
    }

    private boolean crossedTurn(Vec3 start, Vec3 end) {
        for (Vec3 point : pending) {
            if (distanceToLinkSquared(point, start, end) > TURN_TOLERANCE_SQUARED) {
                return true;
            }
        }
        return false;
    }

    private void appendSample(Vec3 position) {
        if (!current.getLast().equals(position)) {
            current.add(position);
            segmentLength += distanceSinceSample;
        }
        distanceSinceSample = 0;
        pending.clear();
    }

    boolean hasRecordedPath() {
        return !current.isEmpty()
                && (current.size() > 1 || !segments.isEmpty() || !current.getLast().equals(lastPosition));
    }

    void beginReturn(int pointLimit) {
        if (pointCount() < pointLimit) {
            appendSample(lastPosition);
        }
        lastRewindPosition = null;
    }

    RewindResult rewind(Vec3 position, double eraseDistance) {
        if (position.equals(lastRewindPosition) && eraseDistance == lastEraseDistance) {
            return RewindResult.UNCHANGED;
        }
        lastRewindPosition = position;
        lastEraseDistance = eraseDistance;

        double bestDistance = eraseDistance * eraseDistance;
        int matchedSegment = -1;
        int matchedPoint = -1;
        int nextStartIndex = pointCount();
        for (int index = segments.size(); index >= 0; index--) {
            Segment segment = index == segments.size() ? null : segments.get(index);
            List<Vec3> points = segment == null ? current : segment.samples();
            int startIndex = segment == null ? currentStartIndex : segment.startIndex();
            // A shared endpoint keeps its index in both segments. Check the anchor separately.
            int lastPoint = Math.min(points.size() - 1, nextStartIndex - startIndex - 1);
            int firstPoint = startIndex == 0 ? 1 : 0;
            for (int point = lastPoint; point >= firstPoint; point--) {
                double distance = position.distanceToSqr(points.get(point));
                if (distance < bestDistance || (matchedPoint < 0 && distance == bestDistance)) {
                    bestDistance = distance;
                    matchedSegment = index;
                    matchedPoint = point;
                }
            }
            nextStartIndex = startIndex;
        }
        if (position.distanceToSqr(anchor) <= bestDistance) {
            current.clear();
            segments.clear();
            currentStartIndex = 0;
            return RewindResult.ARRIVED;
        }
        if (matchedPoint < 0 || (matchedSegment == segments.size() && matchedPoint == current.size() - 1)) {
            return RewindResult.UNCHANGED;
        }
        if (matchedSegment == segments.size()) {
            current.subList(matchedPoint + 1, current.size()).clear();
        } else {
            Segment segment = segments.get(matchedSegment);
            current.clear();
            if (matchedPoint == segment.samples().size() - 1) {
                current.add(segment.end());
                currentStartIndex = segment.startIndex() + matchedPoint;
                segments.subList(matchedSegment + 1, segments.size()).clear();
            } else {
                current.addAll(segment.samples().subList(0, matchedPoint + 1));
                currentStartIndex = segment.startIndex();
                segments.subList(matchedSegment, segments.size()).clear();
            }
        }
        return RewindResult.TRUNCATED;
    }

    void resume(Vec3 position, int pointLimit) {
        finishSegment();
        recordingPaused = false;
        if (pointCount() < pointLimit) {
            startAt(position);
        }
    }

    private void startAt(Vec3 position) {
        if (!current.getFirst().equals(position)) {
            if (currentStartIndex == 0 || (!segments.isEmpty() && segments.getLast().endIndex() == currentStartIndex)) {
                currentStartIndex++;
            }
            current.set(0, position);
        }
        lastPosition = position;
        distanceSinceSample = 0;
        pending.clear();
    }

    private void finishSegment() {
        if (current.size() > 1) {
            segments.add(new Segment(segments.size(), currentStartIndex, List.copyOf(current)));
            currentStartIndex += current.size() - 1;
        }
        Vec3 end = current.getLast();
        current.clear();
        current.add(end);
        segmentLength = 0;
    }

    int pointCount() {
        return currentStartIndex + current.size();
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

    List<Vec3> recordingTail() {
        if (current.getLast().equals(lastPosition)) {
            return current;
        }
        List<Vec3> tail = new ArrayList<>(current);
        tail.add(lastPosition);
        return tail;
    }

    boolean contains(Segment segment) {
        return segment.index() < segments.size() && segments.get(segment.index()) == segment;
    }

    static double closestFraction(Vec3 point, Vec3 start, Vec3 end) {
        double x = end.x - start.x;
        double y = end.y - start.y;
        double z = end.z - start.z;
        double lengthSquared = x * x + y * y + z * z;
        if (lengthSquared == 0) {
            return 0;
        }
        double projection = (point.x - start.x) * x + (point.y - start.y) * y + (point.z - start.z) * z;
        return Math.clamp(projection / lengthSquared, 0, 1);
    }

    private static double distanceToLinkSquared(Vec3 point, Vec3 from, Vec3 to) {
        double fraction = closestFraction(point, from, to);
        double x = from.x + (to.x - from.x) * fraction;
        double y = from.y + (to.y - from.y) * fraction;
        double z = from.z + (to.z - from.z) * fraction;
        return point.distanceToSqr(x, y, z);
    }

    enum RewindResult {
        UNCHANGED, TRUNCATED, ARRIVED
    }

    record Segment(int index, int startIndex, List<Vec3> samples) {
        int endIndex() {
            return startIndex + samples.size() - 1;
        }

        Vec3 start() {
            return samples.getFirst();
        }

        Vec3 end() {
            return samples.getLast();
        }

        boolean isNear(Vec3 position, double rangeSquared) {
            for (int i = 1; i < samples.size(); i++) {
                if (distanceToLinkSquared(position, samples.get(i - 1), samples.get(i)) <= rangeSquared) {
                    return true;
                }
            }
            return false;
        }
    }
}
