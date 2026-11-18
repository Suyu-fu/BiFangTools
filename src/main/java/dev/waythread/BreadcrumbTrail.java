package dev.waythread;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
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
    private Vec3 returnJoinPosition;
    private BreadcrumbTrail returnMovement;

    BreadcrumbTrail(Vec3 anchor) {
        this.anchor = anchor;
        lastPosition = anchor;
        current.add(anchor);
    }

    CompoundTag writeSave() {
        CompoundTag tag = new CompoundTag();
        TrailSave.putPosition(tag, "Anchor", anchor);
        TrailSave.putPosition(tag, "LastPosition", lastPosition);
        TrailSave.putPositions(tag, "Current", current);
        TrailSave.putPositions(tag, "Pending", pending);
        tag.putInt("CurrentStart", currentStartIndex);
        tag.putDouble("DistanceSinceSample", distanceSinceSample);
        tag.putDouble("SegmentLength", segmentLength);
        tag.putBoolean("RecordingPaused", recordingPaused);
        ListTag encodedSegments = new ListTag();
        for (Segment segment : segments) {
            CompoundTag encoded = new CompoundTag();
            encoded.putInt("Start", segment.startIndex());
            TrailSave.putPositions(encoded, "Samples", segment.samples());
            encodedSegments.add(encoded);
        }
        tag.put("Segments", encodedSegments);
        if (returnJoinPosition != null) {
            TrailSave.putPosition(tag, "ReturnJoin", returnJoinPosition);
        }
        if (returnMovement != null) {
            tag.put("ReturnMovement", returnMovement.writeSave());
        }
        return tag;
    }

    static BreadcrumbTrail readSave(CompoundTag tag, int pointLimit, boolean allowReturnMovement) throws IOException {
        BreadcrumbTrail path = new BreadcrumbTrail(TrailSave.position(tag, "Anchor"));
        int previousIndex = 0;
        Vec3 previousEnd = path.anchor;
        ListTag encodedSegments = TrailSave.compounds(tag, "Segments", pointLimit);
        for (int index = 0; index < encodedSegments.size(); index++) {
            CompoundTag encoded = encodedSegments.getCompound(index);
            int start = encoded.getInt("Start");
            List<Vec3> samples = TrailSave.positions(encoded, "Samples", pointLimit);
            TrailSave.require(samples.size() >= 2 && start <= pointLimit - samples.size(), "Invalid route segment size");
            validateLink(start, samples.getFirst(), previousIndex, previousEnd);
            path.addSegment(start, List.copyOf(samples), pathLength(samples));
            previousIndex = start + samples.size() - 1;
            previousEnd = samples.getLast();
        }

        path.currentStartIndex = tag.getInt("CurrentStart");
        List<Vec3> tail = TrailSave.positions(tag, "Current", pointLimit);
        if (tail.isEmpty()) {
            TrailSave.require(path.segments.isEmpty() && path.currentStartIndex == 0, "Invalid finished route");
        } else {
            TrailSave.require(path.currentStartIndex <= pointLimit - tail.size(), "Route exceeds its point limit");
            validateLink(path.currentStartIndex, tail.getFirst(), previousIndex, previousEnd);
        }
        path.current.clear();
        path.current.addAll(tail);
        path.pending.addAll(TrailSave.positions(tag, "Pending", pointLimit));
        path.lastPosition = TrailSave.position(tag, "LastPosition");
        path.distanceSinceSample = tag.getDouble("DistanceSinceSample");
        path.segmentLength = tag.getDouble("SegmentLength");
        TrailSave.require(Double.isFinite(path.distanceSinceSample) && path.distanceSinceSample >= 0
                && Double.isFinite(path.segmentLength) && path.segmentLength >= 0, "Invalid route distance");
        path.recordingPaused = tag.getBoolean("RecordingPaused");
        if (tag.contains("ReturnJoin")) {
            TrailSave.require(allowReturnMovement && !tail.isEmpty(), "Unexpected return join");
            path.returnJoinPosition = TrailSave.position(tag, "ReturnJoin");
        }
        if (tag.contains("ReturnMovement")) {
            int available = pointLimit - path.pointCount() + 1;
            TrailSave.require(path.returnJoinPosition != null && available > 0
                    && tag.contains("ReturnMovement", Tag.TAG_COMPOUND), "Invalid continued return route");
            path.returnMovement = readSave(tag.getCompound("ReturnMovement"), available, false);
            TrailSave.require(path.returnMovement.pointCount() > 0
                    && path.returnMovement.anchor.equals(path.returnJoinPosition), "Invalid return route start");
        }
        return path;
    }

    private static void validateLink(int start, Vec3 first, int previousIndex, Vec3 previousEnd) throws IOException {
        TrailSave.require(start == previousIndex + 1 || (start == previousIndex && first.equals(previousEnd)),
                "Invalid route segment index");
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

    void captureEndpoint(int pointLimit) {
        if (pointCount() < pointLimit) {
            appendSample(lastPosition);
        }
    }

    void beginReturn(int pointLimit) {
        captureEndpoint(pointLimit);
        resetReturnMatch();
    }

    void resetReturnMatch() {
        lastRewindPosition = null;
        setReturnJoin(null);
    }

    RewindResult rewind(Vec3 position, double eraseDistance) {
        return rewind(position, eraseDistance, false);
    }

    RewindResult rewind(Vec3 position, double eraseDistance, boolean keepAnchor) {
        if (position.equals(lastRewindPosition) && eraseDistance == lastEraseDistance) {
            return RewindResult.UNCHANGED;
        }
        lastRewindPosition = position;
        lastEraseDistance = eraseDistance;

        Match match = findMatch(position, eraseDistance);
        if (match == null) {
            return RewindResult.UNCHANGED;
        }
        if (match == Match.ANCHOR) {
            current.clear();
            segments.clear();
            currentStartIndex = 0;
            distanceSinceSample = 0;
            segmentLength = 0;
            pending.clear();
            setReturnJoin(null);
            if (keepAnchor) {
                current.add(anchor);
                lastPosition = anchor;
                return RewindResult.BOUNDARY;
            }
            return RewindResult.ARRIVED;
        }
        int matchedSegment = match.segment();
        int matchedPoint = match.point();
        if (matchedSegment == segments.size() && matchedPoint == current.size() - 1) {
            if (returnJoinPosition == null || current.getLast().equals(position)) {
                setReturnJoin(position);
            }
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
        setReturnJoin(position);
        return RewindResult.TRUNCATED;
    }

    boolean hasPointWithin(Vec3 position, double eraseDistance) {
        return findMatch(position, eraseDistance) != null;
    }

    private Match findMatch(Vec3 position, double eraseDistance) {
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
            return Match.ANCHOR;
        }
        return matchedPoint < 0 ? null : new Match(matchedSegment, matchedPoint);
    }

    private void setReturnJoin(Vec3 position) {
        returnJoinPosition = position;
        returnMovement = null;
    }

    void trackReturnMovement(Vec3 position, double spacing, int pointLimit) {
        if (returnJoinPosition == null) {
            return;
        }
        int movementLimit = returnMovementLimit(pointLimit);
        if (movementLimit < 1 || (returnMovement != null && returnMovement.pointCount() > movementLimit)) {
            setReturnJoin(null);
            return;
        }
        if (returnMovement == null) {
            if (returnJoinPosition.equals(position)) {
                return;
            }
            returnMovement = new BreadcrumbTrail(returnJoinPosition);
        }
        returnMovement.record(position, spacing, movementLimit);
    }

    private int returnMovementLimit(int pointLimit) {
        // The temporary start shares a point with the retained trail only when the positions match.
        return pointLimit - pointCount() + (current.getLast().equals(returnJoinPosition) ? 1 : 0);
    }

    void resume(Vec3 position, double spacing, int pointLimit, double eraseDistance, BiPredicate<Vec3, Vec3> canJoin) {
        if (current.getLast().equals(position)) {
            setReturnJoin(position);
        } else {
            trackReturnMovement(position, spacing, pointLimit);
        }
        finishSegment();
        recordingPaused = false;
        if (pointCount() >= pointLimit) {
            setReturnJoin(null);
            return;
        }
        if (returnJoinPosition == null) {
            startAt(position);
            return;
        }

        Vec3 end = current.getLast();
        int movementLimit = returnMovementLimit(pointLimit);
        if (!end.equals(returnJoinPosition) && end.distanceToSqr(returnJoinPosition) <= eraseDistance * eraseDistance
                && canJoin.test(end, returnJoinPosition)) {
            appendSample(returnJoinPosition);
            finishSegment();
        }
        startAt(returnJoinPosition);
        if (returnMovement != null) {
            if (returnMovement.pointCount() < movementLimit) {
                returnMovement.appendSample(returnMovement.lastPosition);
            }
            appendReturnMovement(returnMovement, pointLimit);
        }
        setReturnJoin(null);
    }

    private void appendReturnMovement(BreadcrumbTrail movement, int pointLimit) {
        int firstMovementIndex = movement.segments.isEmpty() ? movement.currentStartIndex
                : movement.segments.getFirst().startIndex();
        int startIndex = currentStartIsRetained() ? currentStartIndex : currentStartIndex - firstMovementIndex;
        for (Segment segment : movement.segments) {
            addSegment(startIndex + segment.startIndex(), segment.samples(), segment.length());
        }
        currentStartIndex = startIndex + movement.currentStartIndex;
        current.clear();
        current.addAll(movement.current);
        lastPosition = movement.lastPosition;
        segmentLength = movement.segmentLength;
        recordingPaused = movement.recordingPaused && pointCount() < pointLimit;
    }

    private void startAt(Vec3 position) {
        if (!current.getFirst().equals(position)) {
            if (currentStartIsRetained()) {
                currentStartIndex++;
            }
            current.set(0, position);
        }
        lastPosition = position;
        distanceSinceSample = 0;
        pending.clear();
    }

    private boolean currentStartIsRetained() {
        return currentStartIndex == 0 || (!segments.isEmpty() && segments.getLast().endIndex() == currentStartIndex);
    }

    private void finishSegment() {
        if (current.size() > 1) {
            addSegment(currentStartIndex, List.copyOf(current), pathLength(current));
            currentStartIndex += current.size() - 1;
        }
        Vec3 end = current.getLast();
        current.clear();
        current.add(end);
        segmentLength = 0;
    }

    private void addSegment(int startIndex, List<Vec3> samples, double length) {
        segments.add(new Segment(segments.size(), startIndex, samples, startDistanceAt(startIndex), length));
    }

    private double startDistanceAt(int startIndex) {
        if (!segments.isEmpty()) {
            Segment previous = segments.getLast();
            if (previous.endIndex() == startIndex) {
                return previous.startDistance() + previous.length();
            }
        }
        return 0;
    }

    double currentStartDistance() {
        return startDistanceAt(currentStartIndex);
    }

    static double pathLength(List<Vec3> points) {
        double length = 0;
        for (int i = 1; i < points.size(); i++) {
            length += points.get(i - 1).distanceTo(points.get(i));
        }
        return length;
    }

    int pointCount() {
        return currentStartIndex + current.size();
    }

    Vec3 anchor() {
        return anchor;
    }

    Vec3 endpoint() {
        return current.getLast();
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
        UNCHANGED, TRUNCATED, BOUNDARY, ARRIVED
    }

    private record Match(int segment, int point) {
        private static final Match ANCHOR = new Match(-1, -1);
    }

    record Segment(int index, int startIndex, List<Vec3> samples, double startDistance, double length) {
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
