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
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

final class DimensionalTrail {
    private final List<Visit> visits = new ArrayList<>();
    private final Map<ResourceKey<Level>, List<Visit>> byDimension = new HashMap<>();
    // Sealed visits keep their point counts until they become the active visit again.
    private int earlierPoints;
    private boolean recordingGap;
    private boolean pointLimitNotified;
    private ReturnState returnState = ReturnState.ON_ROUTE;

    DimensionalTrail(ResourceKey<Level> dimension, Vec3 anchor) {
        appendVisit(dimension, anchor, false);
    }

    private DimensionalTrail() {
    }

    CompoundTag writeSave() {
        CompoundTag tag = new CompoundTag();
        tag.putString("ReturnState", returnState.name());
        tag.putBoolean("RecordingGap", recordingGap);
        ListTag encodedVisits = new ListTag();
        for (Visit visit : visits) {
            CompoundTag encoded = new CompoundTag();
            encoded.putString("Dimension", visit.dimension().location().toString());
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
            CompoundTag encoded = encodedVisits.getCompound(index);
            ResourceLocation id = ResourceLocation.tryParse(encoded.getString("Dimension"));
            TrailSave.require(id != null && !id.getPath().isEmpty(), "Invalid route dimension");
            ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, id);
            boolean linked = encoded.getBoolean("Linked");
            TrailSave.require(!linked || (previousDimension != null && !previousDimension.equals(dimension)),
                    "Invalid route dimension boundary");
            BreadcrumbTrail path = BreadcrumbTrail.readSave(encoded.getCompound("Path"), pointLimit - points,
                    index == encodedVisits.size() - 1);
            TrailSave.require(path.pointCount() > 0 || encodedVisits.size() == 1, "Empty route visit");
            trail.appendVisit(new Visit(dimension, path, linked));
            points += path.pointCount();
            previousDimension = dimension;
        }
        trail.returnState = ReturnState.valueOf(tag.getString("ReturnState"));
        TrailSave.require(trail.returnState != ReturnState.AT_BOUNDARY
                || (trail.visits.size() > 1 && trail.visits.getLast().path().pointCount() == 1),
                "Invalid return boundary");
        trail.recordingGap = tag.getBoolean("RecordingGap");
        return trail;
    }

    void restoreDimension(ResourceKey<Level> dimension, boolean returning) {
        if (activePath(dimension) == null) {
            if (!returning) {
                recordingGap = true;
            } else if (returnState == ReturnState.ON_ROUTE) {
                returnState = ReturnState.DETACHED;
            }
        }
    }

    int pointCount() {
        return earlierPoints + visits.getLast().path().pointCount();
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
                last.path().captureEndpoint(pointLimit - earlierPoints);
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
        returnState = !recordingGap && activePath(dimension) != null ? ReturnState.ON_ROUTE : ReturnState.DETACHED;
    }

    boolean waitingToRejoin() {
        return returnState != ReturnState.ON_ROUTE;
    }

    ResourceKey<Level> boundaryDestination() {
        return returnState == ReturnState.AT_BOUNDARY ? previousVisit().dimension() : null;
    }

    BreadcrumbTrail.RewindResult rewind(ResourceKey<Level> dimension, Vec3 position, double eraseDistance) {
        BreadcrumbTrail path = activePath(dimension);
        if (waitingToRejoin() || path == null) {
            return BreadcrumbTrail.RewindResult.UNCHANGED;
        }
        BreadcrumbTrail.RewindResult result = visits.size() > 1
                ? path.rewind(position, eraseDistance, true) : path.rewind(position, eraseDistance);
        if (result == BreadcrumbTrail.RewindResult.BOUNDARY) {
            returnState = ReturnState.AT_BOUNDARY;
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
        if (returnState != ReturnState.DETACHED && departure != null && previous != null && last.linkedToPrevious()
                && last.dimension().equals(from) && previous.dimension().equals(to)
                && departure.distanceToSqr(last.path().anchor()) <= rangeSquared
                && arrival.distanceToSqr(previous.path().endpoint()) <= rangeSquared) {
            selectVisit(previous);
            return true;
        }
        if (returnState == ReturnState.ON_ROUTE) {
            returnState = ReturnState.DETACHED;
        }
        last.path().resetReturnMatch();
        return false;
    }

    RejoinResult rejoin(ResourceKey<Level> dimension, Vec3 position, double eraseDistance) {
        if (!waitingToRejoin()) {
            return RejoinResult.NO_MATCH;
        }
        Visit last = visits.getLast();
        Visit preferred = returnState == ReturnState.AT_BOUNDARY ? previousVisit() : last;
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
        returnState = ReturnState.ON_ROUTE;
        pointLimitNotified = false;
    }

    private void appendVisit(ResourceKey<Level> dimension, Vec3 position, boolean linkedToPrevious) {
        appendVisit(new Visit(dimension, new BreadcrumbTrail(position), linkedToPrevious));
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
        returnState = ReturnState.ON_ROUTE;
        recordingGap = false;
    }

    private record Visit(ResourceKey<Level> dimension, BreadcrumbTrail path, boolean linkedToPrevious) {
    }

    private enum ReturnState {
        ON_ROUTE, AT_BOUNDARY, DETACHED
    }

    enum Marker {
        NONE, START, RELAY
    }

    enum RejoinResult {
        REJOINED, ARRIVED, NO_MATCH, OVERLAP
    }
}
