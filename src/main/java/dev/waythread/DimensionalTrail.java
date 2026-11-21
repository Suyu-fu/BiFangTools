package dev.waythread;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

final class DimensionalTrail {
    private final List<Visit> visits = new ArrayList<>();
    private final Map<ResourceKey<Level>, List<Visit>> byDimension = new HashMap<>();
    // Sealed visits keep their point counts until they become the active visit again.
    private int earlierPoints;
    private boolean recordingGap;
    private boolean pointLimitNotified;
    private RouteAlignment returnAlignment = RouteAlignment.ON_ROUTE;

    DimensionalTrail(ResourceKey<Level> dimension, Vec3 anchor) {
        appendVisit(dimension, anchor, false);
    }

    private DimensionalTrail() {
    }

    CompoundTag writeSave() {
        CompoundTag tag = new CompoundTag();
        tag.putString("ReturnState", returnAlignment.name());
        tag.putBoolean("RecordingGap", recordingGap);
        ListTag encodedVisits = new ListTag();
        for (Visit visit : visits) {
            CompoundTag encoded = new CompoundTag();
            encoded.putString("Dimension", visit.dimension().identifier().toString());
            encoded.putBoolean("Linked", visit.linkedToPrevious());
            encoded.put("Path", visit.path().writeSave());
            encodedVisits.add(encoded);
        }
        tag.put("Visits", encodedVisits);
        return tag;
    }

    static DimensionalTrail readSave(CompoundTag tag) throws IOException {
        int pointLimit = WaythreadConfig.MAX_RECORDED_POINT_LIMIT;
        ListTag encodedVisits = TrailSave.compounds(tag, "Visits", pointLimit);
        TrailSave.require(!encodedVisits.isEmpty(), "No route visits");
        DimensionalTrail trail = new DimensionalTrail();
        int points = 0;
        ResourceKey<Level> previousDimension = null;
        for (int index = 0; index < encodedVisits.size(); index++) {
            CompoundTag encoded = (CompoundTag) encodedVisits.get(index);
            Identifier id = Identifier.tryParse(encoded.getStringOr("Dimension", ""));
            TrailSave.require(id != null && !id.getPath().isEmpty(), "Invalid route dimension");
            ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, id);
            boolean linked = encoded.getBooleanOr("Linked", false);
            TrailSave.require(!linked || (previousDimension != null && !previousDimension.equals(dimension)),
                    "Invalid route dimension boundary");
            BreadcrumbTrail path = BreadcrumbTrail.readSave(encoded.getCompoundOrEmpty("Path"), pointLimit - points,
                    index == encodedVisits.size() - 1);
            TrailSave.require(path.pointCount() > 0 || encodedVisits.size() == 1, "Empty route visit");
            trail.appendVisit(new Visit(dimension, path, linked));
            points += path.pointCount();
            previousDimension = dimension;
        }
        trail.returnAlignment = RouteAlignment.valueOf(tag.getStringOr("ReturnState", ""));
        TrailSave.require(trail.returnAlignment != RouteAlignment.AT_BOUNDARY
                || (trail.visits.size() > 1 && trail.visits.getLast().path().pointCount() == 1),
                "Invalid return boundary");
        trail.recordingGap = tag.getBooleanOr("RecordingGap", false);
        return trail;
    }

    void restoreDimension(ResourceKey<Level> dimension, boolean returning) {
        if (activePath(dimension) == null) {
            if (!returning) {
                recordingGap = true;
            } else if (returnAlignment == RouteAlignment.ON_ROUTE) {
                returnAlignment = RouteAlignment.DETACHED;
            }
        }
    }

    int pointCount() {
        return earlierPoints + visits.getLast().path().pointCount();
    }

    int visitCount() {
        return visits.size();
    }

    Visit visit(int index) {
        return visits.get(index);
    }

    List<Visit> visitsIn(ResourceKey<Level> dimension) {
        return byDimension.getOrDefault(dimension, List.of());
    }

    void setRecordingMode(BreadcrumbTrail.RecordingMode mode) {
        visits.getLast().path().setRecordingMode(mode);
    }

    void freezeForRecovery(ResourceKey<Level> dimension, Vec3 position, boolean returning,
            double spacing, int pointLimit, double eraseDistance, BiPredicate<Vec3, Vec3> canJoin) {
        BreadcrumbTrail path = activePath(dimension);
        if (!waitingToRejoin() && !recordingGap && path != null) {
            if (returning) {
                path.resume(position, spacing, pointLimit - earlierPoints, eraseDistance, canJoin);
            } else if (path.pointCount() < pointLimit - earlierPoints) {
                path.record(position, spacing, pointLimit - earlierPoints);
            }
            path.captureEndpoint(pointLimit - earlierPoints);
        }
        visits.getLast().path().resetReturnMatch();
        returnAlignment = RouteAlignment.ON_ROUTE;
    }

    void continueAfterRecovery(ResourceKey<Level> dimension, Vec3 position, int pointLimit,
            double eraseDistance, BiPredicate<Vec3, Vec3> canJoin) {
        BreadcrumbTrail path = activePath(dimension);
        if (path != null) {
            Vec3 endpoint = path.endpoint();
            boolean joinEndpoint = !recordingGap && !endpoint.equals(position)
                    && endpoint.distanceToSqr(position) <= eraseDistance * eraseDistance
                    && canJoin.test(endpoint, position);
            path.continueRecording(position, pointLimit - earlierPoints, joinEndpoint);
            recordingGap = false;
        } else if (pointCount() < pointLimit) {
            appendVisit(dimension, position, false);
            recordingGap = false;
        } else {
            recordingGap = true;
        }
        pointLimitNotified = false;
    }

    boolean hasRecordedPath() {
        return visits.size() > 1 || visits.getLast().path().hasRecordedPath();
    }

    private BreadcrumbTrail activePath(ResourceKey<Level> dimension) {
        Visit last = visits.getLast();
        return last.dimension().equals(dimension) ? last.path() : null;
    }

    BreadcrumbTrail visiblePath(ResourceKey<Level> dimension) {
        BreadcrumbTrail active = activePath(dimension);
        if (active != null || !waitingToRejoin()) {
            return active;
        }
        List<Visit> localVisits = byDimension.get(dimension);
        return localVisits == null ? null : localVisits.getLast().path();
    }

    Marker markerFor(BreadcrumbTrail path) {
        if (path != visits.getLast().path()) {
            return Marker.NONE;
        }
        return visits.size() == 1 ? Marker.START : Marker.RELAY;
    }

    boolean record(ResourceKey<Level> dimension, Vec3 position, double spacing, int pointLimit) {
        Visit last = visits.getLast();
        if (recordingGap || !last.dimension().equals(dimension)) {
            if (!recordingGap) {
                last.path().captureDeparture(pointLimit - earlierPoints);
            }
            if (pointCount() < pointLimit) {
                appendVisit(dimension, position, !recordingGap);
                recordingGap = false;
            } else {
                recordingGap = true;
            }
        }
        if (!recordingGap) {
            visits.getLast().path().record(position, spacing, pointLimit - earlierPoints);
        }
        boolean full = pointCount() >= pointLimit;
        boolean notify = full && !pointLimitNotified;
        pointLimitNotified = full;
        return notify;
    }

    void beginReturn(ResourceKey<Level> dimension, int pointLimit) {
        visits.getLast().path().beginReturn(pointLimit - earlierPoints);
        returnAlignment = !recordingGap && activePath(dimension) != null
                ? RouteAlignment.ON_ROUTE : RouteAlignment.DETACHED;
    }

    boolean waitingToRejoin() {
        return returnAlignment.waitingToRejoin();
    }

    RouteAlignment returnAlignment() {
        return returnAlignment;
    }

    ResourceKey<Level> boundaryDestination() {
        return returnAlignment == RouteAlignment.AT_BOUNDARY ? previousVisit().dimension() : null;
    }

    BreadcrumbTrail.RewindResult rewind(ResourceKey<Level> dimension, Vec3 position, double eraseDistance) {
        BreadcrumbTrail path = activePath(dimension);
        if (waitingToRejoin() || path == null) {
            return BreadcrumbTrail.RewindResult.UNCHANGED;
        }
        BreadcrumbTrail.RewindResult result = visits.size() > 1
                ? path.rewind(position, eraseDistance, true) : path.rewind(position, eraseDistance);
        if (result == BreadcrumbTrail.RewindResult.BOUNDARY) {
            returnAlignment = RouteAlignment.AT_BOUNDARY;
        }
        return result;
    }

    void trackReturnMovement(Vec3 position, double spacing, int pointLimit) {
        visits.getLast().path().trackReturnMovement(position, spacing, pointLimit - earlierPoints);
    }

    boolean crossBack(ResourceKey<Level> from, Vec3 departure, ResourceKey<Level> to, Vec3 arrival,
            double eraseDistance) {
        Visit last = visits.getLast();
        Visit previous = previousVisit();
        double rangeSquared = eraseDistance * eraseDistance;
        if (returnAlignment != RouteAlignment.DETACHED && previous != null && last.linkedToPrevious()
                && new RouteTarget(last.dimension(), last.path().anchor()).isWithin(from, departure, rangeSquared)
                && new RouteTarget(previous.dimension(), previous.path().endpoint()).isWithin(to, arrival, rangeSquared)) {
            selectVisit(previous);
            return true;
        }
        if (returnAlignment == RouteAlignment.ON_ROUTE) {
            returnAlignment = RouteAlignment.DETACHED;
        }
        last.path().resetReturnMatch();
        return false;
    }

    RejoinResult rejoin(ResourceKey<Level> dimension, Vec3 position, double eraseDistance) {
        if (!waitingToRejoin()) {
            return RejoinResult.NO_MATCH;
        }
        Visit last = visits.getLast();
        Visit preferred = returnAlignment == RouteAlignment.AT_BOUNDARY ? previousVisit() : last;
        Visit match = null;
        if (preferred != null && preferred.dimension().equals(dimension)
                && preferred.path().hasPointWithin(position, eraseDistance)) {
            match = preferred;
        } else {
            for (Visit visit : byDimension.getOrDefault(dimension, List.of())) {
                if (visit == last || visit == preferred || !visit.path().hasPointWithin(position, eraseDistance)) {
                    continue;
                }
                if (match != null) {
                    return RejoinResult.OVERLAP;
                }
                match = visit;
            }
        }
        if (match == null) {
            return RejoinResult.NO_MATCH;
        }
        selectVisit(match);
        return rewind(dimension, position, eraseDistance) == BreadcrumbTrail.RewindResult.ARRIVED
                ? RejoinResult.ARRIVED : RejoinResult.REJOINED;
    }

    void resume(ResourceKey<Level> dimension, Vec3 position, double spacing, int pointLimit,
            double eraseDistance, BiPredicate<Vec3, Vec3> canJoin) {
        BreadcrumbTrail path = activePath(dimension);
        if (waitingToRejoin() || path == null) {
            recordingGap = true;
        } else {
            path.resume(position, spacing, pointLimit - earlierPoints, eraseDistance, canJoin);
            recordingGap = false;
        }
        returnAlignment = RouteAlignment.ON_ROUTE;
        pointLimitNotified = false;
    }

    private void appendVisit(ResourceKey<Level> dimension, Vec3 position, boolean linkedToPrevious) {
        BreadcrumbTrail path = new BreadcrumbTrail(position);
        if (!visits.isEmpty()) {
            path.setRecordingMode(visits.getLast().path().recordingMode());
        }
        appendVisit(new Visit(dimension, path, linkedToPrevious));
    }

    private void appendVisit(Visit visit) {
        if (!visits.isEmpty()) {
            earlierPoints = pointCount();
        }
        visits.add(visit);
        byDimension.computeIfAbsent(visit.dimension(), key -> new ArrayList<>()).add(visit);
    }

    private Visit previousVisit() {
        return visits.size() > 1 ? visits.get(visits.size() - 2) : null;
    }

    private void selectVisit(Visit target) {
        while (visits.getLast() != target) {
            Visit removed = visits.removeLast();
            List<Visit> inDimension = byDimension.get(removed.dimension());
            // Both lists use recording order, so this visit is also the last one in its dimension.
            inDimension.removeLast();
            if (inDimension.isEmpty()) {
                byDimension.remove(removed.dimension());
            }
            earlierPoints -= visits.getLast().path().pointCount();
        }
        target.path().resetReturnMatch();
        returnAlignment = RouteAlignment.ON_ROUTE;
        recordingGap = false;
    }

    record Visit(ResourceKey<Level> dimension, BreadcrumbTrail path, boolean linkedToPrevious) {
    }

    enum Marker {
        NONE, START, RELAY
    }

    enum RejoinResult {
        REJOINED, ARRIVED, NO_MATCH, OVERLAP
    }
}
