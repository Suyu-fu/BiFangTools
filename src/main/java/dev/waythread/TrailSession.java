package dev.waythread;

import java.util.function.BiPredicate;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

final class TrailSession {
    private State state = Idle.INSTANCE;

    Waythread.Mode mode() {
        return state.mode();
    }

    DimensionalTrail trail() {
        return state.trail();
    }

    RecoveryRoute recovery() {
        return state instanceof Recovery recovery ? recovery.route() : null;
    }

    int returnStartPointCount() {
        return state instanceof Returning returning ? returning.startingPoints() : 0;
    }

    void startRecording(ResourceKey<Level> dimension, Vec3 position) {
        state = new Recording(new DimensionalTrail(dimension, position));
    }

    boolean beginReturn(ResourceKey<Level> dimension, int pointLimit) {
        if (!(state instanceof Recording recording) || !recording.trail().hasRecordedPath()) {
            return false;
        }
        DimensionalTrail trail = recording.trail();
        trail.beginReturn(dimension, pointLimit);
        state = new Returning(trail, trail.pointCount());
        return true;
    }

    void resumeRecording(ResourceKey<Level> dimension, Vec3 position, BreadcrumbTrail.RecordingMode recordingMode,
            double spacing, int pointLimit, double eraseDistance, BiPredicate<Vec3, Vec3> canJoin) {
        DimensionalTrail trail = state.trail();
        trail.setRecordingMode(recordingMode);
        trail.resume(dimension, position, spacing, pointLimit, eraseDistance, canJoin);
        state = new Recording(trail);
    }

    boolean pauseForDeath(ResourceKey<Level> dimension, Vec3 position, BreadcrumbTrail.RecordingMode recordingMode,
            double spacing, int pointLimit, double eraseDistance, BiPredicate<Vec3, Vec3> canJoin) {
        if (state instanceof Recovery recovery) {
            if (!recovery.route().active()) {
                return false;
            }
            recovery.route().resetProgress();
            return true;
        }
        if (state instanceof Idle || state instanceof Arrived) {
            return false;
        }
        DimensionalTrail trail = state.trail();
        trail.setRecordingMode(recordingMode);
        trail.freezeForRecovery(dimension, position, state instanceof Returning, spacing, pointLimit, eraseDistance, canJoin);
        state = new Recovery(trail, new RecoveryRoute(dimension, position));
        return true;
    }

    boolean startRecovery(ResourceKey<Level> dimension, Vec3 position, double range) {
        return state instanceof Recovery recovery
                && recovery.route().start(recovery.trail(), dimension, position, range);
    }

    void finishRecovery(ResourceKey<Level> dimension, Vec3 position, int pointLimit, double eraseDistance,
            BiPredicate<Vec3, Vec3> canJoin) {
        DimensionalTrail trail = state.trail();
        trail.continueAfterRecovery(dimension, position, pointLimit, eraseDistance, canJoin);
        state = new Recording(trail);
    }

    void finishReturn(boolean clearOnArrival) {
        state = clearOnArrival ? Idle.INSTANCE : new Arrived(state.trail());
    }

    void clear() {
        state = Idle.INSTANCE;
    }

    void restore(TrailSave saved) {
        state = switch (saved.mode()) {
            case IDLE -> Idle.INSTANCE;
            case RECORDING -> new Recording(saved.trail());
            case RETURNING -> new Returning(saved.trail(), saved.returnStartPointCount());
            case ARRIVED -> new Arrived(saved.trail());
            case WAITING_FOR_RECOVERY, RECOVERING -> new Recovery(saved.trail(), saved.recovery());
        };
    }

    TrailSave.Snapshot snapshot() {
        return state instanceof Idle ? null
                : new TrailSave(trail(), mode(), returnStartPointCount(), recovery()).snapshot();
    }

    private sealed interface State permits Idle, Recording, Returning, Arrived, Recovery {
        Waythread.Mode mode();

        DimensionalTrail trail();
    }

    private enum Idle implements State {
        INSTANCE;

        public Waythread.Mode mode() {
            return Waythread.Mode.IDLE;
        }

        public DimensionalTrail trail() {
            return null;
        }
    }

    private record Recording(DimensionalTrail trail) implements State {
        public Waythread.Mode mode() {
            return Waythread.Mode.RECORDING;
        }
    }

    private record Returning(DimensionalTrail trail, int startingPoints) implements State {
        public Waythread.Mode mode() {
            return Waythread.Mode.RETURNING;
        }
    }

    private record Arrived(DimensionalTrail trail) implements State {
        public Waythread.Mode mode() {
            return Waythread.Mode.ARRIVED;
        }
    }

    private record Recovery(DimensionalTrail trail, RecoveryRoute route) implements State {
        public Waythread.Mode mode() {
            return route.active() ? Waythread.Mode.RECOVERING : Waythread.Mode.WAITING_FOR_RECOVERY;
        }
    }
}
