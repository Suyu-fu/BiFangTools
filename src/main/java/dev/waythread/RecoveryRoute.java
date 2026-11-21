package dev.waythread;

import java.io.IOException;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

final class RecoveryRoute {
    private final RouteTarget deathTarget;
    private int visitIndex = -1;
    private int pointIndex;
    private int earlierPoints;
    private ResourceKey<Level> lastMatchDimension;
    private Vec3 lastMatchPosition;
    private double lastMatchRange;

    RecoveryRoute(ResourceKey<Level> deathDimension, Vec3 deathPosition) {
        deathTarget = new RouteTarget(deathDimension, deathPosition);
    }

    boolean active() {
        return visitIndex >= 0;
    }

    boolean start(DimensionalTrail trail, ResourceKey<Level> dimension, Vec3 position, double range) {
        if (active()) {
            return false;
        }
        double bestDistance = Double.POSITIVE_INFINITY;
        int precedingPoints = 0;
        for (int index = 0; index < trail.visitCount(); index++) {
            DimensionalTrail.Visit visit = trail.visit(index);
            BreadcrumbTrail path = visit.path();
            if (visit.dimension().equals(dimension)) {
                int match = path.matchForward(position, range, 0);
                if (match >= 0) {
                    Vec3 point = match == 0 ? path.anchor() : path.pointAt(match);
                    double distance = position.distanceToSqr(point);
                    // Visit order resolves ties at intersections and repeated dimension visits.
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        visitIndex = index;
                        pointIndex = match;
                        earlierPoints = precedingPoints;
                        if (distance == 0) {
                            break;
                        }
                    }
                }
            }
            precedingPoints += path.pointCount();
        }
        return active();
    }

    void resetProgress() {
        visitIndex = -1;
        pointIndex = 0;
        earlierPoints = 0;
        lastMatchPosition = null;
    }

    int pointIndex() {
        return pointIndex;
    }

    int remainingPoints(DimensionalTrail trail) {
        return trail.pointCount() - earlierPoints - pointIndex;
    }

    BreadcrumbTrail visiblePath(DimensionalTrail trail, ResourceKey<Level> dimension) {
        DimensionalTrail.Visit visit = trail.visit(active() ? visitIndex : 0);
        return visit.dimension().equals(dimension) ? visit.path() : null;
    }

    RouteTarget marker(DimensionalTrail trail, ResourceKey<Level> dimension) {
        if (!active()) {
            DimensionalTrail.Visit first = trail.visit(0);
            return first.dimension().equals(dimension) ? new RouteTarget(dimension, first.path().anchor()) : null;
        }
        DimensionalTrail.Visit visit = trail.visit(visitIndex);
        if (visitIndex == trail.visitCount() - 1 && deathTarget.dimension().equals(dimension)
                && (visit.dimension().equals(dimension) || atVisitEnd(visit.path()))) {
            return deathTarget;
        }
        if (visit.dimension().equals(dimension)) {
            return new RouteTarget(dimension, visit.path().endpoint());
        }
        if (atVisitEnd(visit.path())) {
            RouteTarget entry = nextTarget(trail);
            return entry.dimension().equals(dimension) ? entry : null;
        }
        return rejoinTarget(trail, dimension);
    }

    RouteTarget rejoinTarget(DimensionalTrail trail, ResourceKey<Level> dimension) {
        if (!active() || visitIndex + 1 == trail.visitCount() || atVisitEnd(trail.visit(visitIndex).path())) {
            return null;
        }
        DimensionalTrail.Visit next = trail.visit(visitIndex + 1);
        return next.linkedToPrevious() && next.dimension().equals(dimension)
                ? new RouteTarget(dimension, next.path().anchor()) : null;
    }

    boolean rejoin(DimensionalTrail trail, ResourceKey<Level> dimension, Vec3 position, double range) {
        RouteTarget entry = rejoinTarget(trail, dimension);
        if (entry == null || !entry.isWithin(dimension, position, range * range)) {
            return false;
        }
        enterNextVisit(trail);
        return true;
    }

    RouteAlignment alignment(DimensionalTrail trail, ResourceKey<Level> dimension) {
        DimensionalTrail.Visit visit = trail.visit(visitIndex);
        boolean inCurrentVisit = visit.dimension().equals(dimension);
        if (atVisitEnd(visit.path())
                && (visitIndex + 1 < trail.visitCount() || !visit.dimension().equals(deathTarget.dimension()))) {
            return inCurrentVisit || nextTarget(trail).dimension().equals(dimension)
                    ? RouteAlignment.AT_BOUNDARY : RouteAlignment.DETACHED;
        }
        return inCurrentVisit ? RouteAlignment.ON_ROUTE : RouteAlignment.DETACHED;
    }

    RouteTarget nextTarget(DimensionalTrail trail) {
        DimensionalTrail.Visit visit = trail.visit(visitIndex);
        if (!atVisitEnd(visit.path())) {
            return new RouteTarget(visit.dimension(), visit.path().pointAt(pointIndex + 1));
        }
        if (visitIndex + 1 < trail.visitCount()) {
            DimensionalTrail.Visit next = trail.visit(visitIndex + 1);
            return new RouteTarget(next.dimension(), next.path().anchor());
        }
        return deathTarget;
    }

    private boolean atVisitEnd(BreadcrumbTrail path) {
        return pointIndex == path.pointCount() - 1;
    }

    Step advance(DimensionalTrail trail, ResourceKey<Level> dimension, Vec3 position, double range) {
        if (position.equals(lastMatchPosition) && dimension.equals(lastMatchDimension) && range == lastMatchRange) {
            return Step.WAITING;
        }
        lastMatchPosition = position;
        lastMatchDimension = dimension;
        lastMatchRange = range;
        if (alignment(trail, dimension) == RouteAlignment.DETACHED) {
            return Step.WAITING;
        }
        BreadcrumbTrail path = trail.visit(visitIndex).path();
        if (!atVisitEnd(path)) {
            int match = path.matchForward(position, range, pointIndex);
            if (match <= pointIndex) {
                return Step.WAITING;
            }
            pointIndex = match;
            if (atVisitEnd(path)) {
                // The next check now tests the death target or the next visit's entrance.
                lastMatchPosition = null;
            }
            return Step.ADVANCED;
        }

        if (!nextTarget(trail).isWithin(dimension, position, range * range)) {
            return Step.WAITING;
        }
        if (visitIndex + 1 == trail.visitCount()) {
            return Step.ARRIVED;
        }
        enterNextVisit(trail);
        return Step.ADVANCED;
    }

    private void enterNextVisit(DimensionalTrail trail) {
        earlierPoints += trail.visit(visitIndex).path().pointCount();
        visitIndex++;
        pointIndex = 0;
        lastMatchPosition = null;
    }

    CompoundTag writeSave() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Dimension", deathTarget.dimension().identifier().toString());
        TrailSave.putPosition(tag, "Position", deathTarget.position());
        tag.putInt("Visit", visitIndex);
        tag.putInt("Point", pointIndex);
        return tag;
    }

    static RecoveryRoute readSave(CompoundTag tag, DimensionalTrail trail) throws IOException {
        Identifier id = Identifier.tryParse(tag.getStringOr("Dimension", ""));
        TrailSave.require(id != null && !id.getPath().isEmpty(), "Invalid recovery dimension");
        TrailSave.require(tag.get("Visit") instanceof IntTag && tag.get("Point") instanceof IntTag,
                "Missing recovery progress");
        int visit = tag.getIntOr("Visit", 0);
        int point = tag.getIntOr("Point", 0);
        TrailSave.require(visit >= -1 && visit < trail.visitCount() && point >= 0
                && (visit < 0 ? point == 0 : point < trail.visit(visit).path().pointCount()),
                "Invalid recovery progress");
        RecoveryRoute recovery = new RecoveryRoute(ResourceKey.create(Registries.DIMENSION, id),
                TrailSave.position(tag, "Position"));
        recovery.visitIndex = visit;
        recovery.pointIndex = point;
        for (int index = 0; index < visit; index++) {
            recovery.earlierPoints += trail.visit(index).path().pointCount();
        }
        return recovery;
    }

    enum Step {
        WAITING, ADVANCED, ARRIVED
    }
}
