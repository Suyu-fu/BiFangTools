package dev.waythread;

import com.google.common.hash.Hashing;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.phys.Vec3;

record TrailSave(DimensionalTrail trail, Waythread.Mode mode, int returnStartPointCount, RecoveryRoute recovery) {
    private static final int FORMAT_VERSION = 1;
    private static final int RECOVERY_FORMAT_VERSION = 2;
    private static final int MAX_NBT_BYTES = 32 * 1024 * 1024;

    TrailSave(DimensionalTrail trail, Waythread.Mode mode, int returnStartPointCount) {
        this(trail, mode, returnStartPointCount, null);
    }

    static Path fileFor(Path clientConfig, Path worldDirectory, long worldSeed) throws IOException {
        String world = worldDirectory.toRealPath().toString();
        String key = Hashing.sha256().hashString(world + '\n' + worldSeed, StandardCharsets.UTF_8).toString();
        return clientConfig.resolve("bifangtools").resolve("waythread").resolve(key + ".dat");
    }

    static TrailSave read(Path file) throws IOException {
        if (Files.notExists(file)) {
            return null;
        }
        return readTag(NbtIo.readCompressed(file, NbtAccounter.create(MAX_NBT_BYTES)));
    }

    private static TrailSave readTag(CompoundTag tag) throws IOException {
        int version = tag.getIntOr("Version", 0);
        require(version == FORMAT_VERSION || version == RECOVERY_FORMAT_VERSION, "Unsupported route save version");
        boolean hasRecovery = version == RECOVERY_FORMAT_VERSION;
        String savedMode = tag.getStringOr("Mode", "");
        require(hasRecovery == savedMode.equals("RECOVERY"), "Recovery state does not match its format");
        DimensionalTrail trail = DimensionalTrail.readSave(tag.getCompoundOrEmpty("Route"));
        RecoveryRoute recovery = hasRecovery ? RecoveryRoute.readSave(tag.getCompoundOrEmpty("Recovery"), trail) : null;
        Waythread.Mode mode;
        if (recovery == null) {
            mode = Waythread.Mode.valueOf(savedMode);
            require(mode != Waythread.Mode.IDLE && !mode.isRecovery(), "Unexpected saved mode");
        } else {
            mode = recovery.active() ? Waythread.Mode.RECOVERING : Waythread.Mode.WAITING_FOR_RECOVERY;
        }
        int startingPoints = tag.getIntOr("ReturnStart", 0);
        int points = trail.pointCount();
        require((mode == Waythread.Mode.ARRIVED) == (points == 0), "Route count does not match its mode");
        if (mode == Waythread.Mode.RETURNING) {
            require(startingPoints >= points && startingPoints <= WaythreadConfig.MAX_RECORDED_POINT_LIMIT,
                    "Invalid return progress");
        } else {
            require(startingPoints == 0 && !trail.waitingToRejoin(), "Unexpected return state");
        }
        return new TrailSave(trail, mode, startingPoints, recovery);
    }

    void write(Path file) throws IOException {
        snapshot().write(file);
    }

    Snapshot snapshot() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Version", recovery == null ? FORMAT_VERSION : RECOVERY_FORMAT_VERSION);
        tag.putString("Mode", recovery == null ? mode.name() : "RECOVERY");
        tag.putInt("ReturnStart", returnStartPointCount);
        tag.put("Route", trail.writeSave());
        if (recovery != null) {
            tag.put("Recovery", recovery.writeSave());
        }
        return new Snapshot(tag);
    }

    static final class Snapshot {
        // The tag owns its arrays and lists. Neither reading nor writing changes them.
        private final CompoundTag tag;

        private Snapshot(CompoundTag tag) {
            this.tag = tag;
        }

        TrailSave read() throws IOException {
            return readTag(tag);
        }

        void write(Path file) throws IOException {
            Path directory = file.getParent();
            Files.createDirectories(directory);
            Path temporary = Files.createTempFile(directory, "waythread-", ".tmp");
            try {
                NbtIo.writeCompressed(tag, temporary);
                try {
                    Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
    }

    static void putPosition(CompoundTag tag, String name, Vec3 position) {
        putPositions(tag, name, List.of(position));
    }

    static void putPositions(CompoundTag tag, String name, List<Vec3> positions) {
        long[] data = new long[positions.size() * 3];
        int index = 0;
        for (Vec3 position : positions) {
            data[index++] = Double.doubleToLongBits(position.x);
            data[index++] = Double.doubleToLongBits(position.y);
            data[index++] = Double.doubleToLongBits(position.z);
        }
        tag.putLongArray(name, data);
    }

    static Vec3 position(CompoundTag tag, String name) throws IOException {
        List<Vec3> points = positions(tag, name, 1);
        require(points.size() == 1, "Missing route position");
        return points.getFirst();
    }

    static List<Vec3> positions(CompoundTag tag, String name, int limit) throws IOException {
        long[] data = tag.getLongArray(name).orElseThrow(() -> new IOException("Missing route position array"));
        require(data.length % 3 == 0 && data.length / 3 <= limit, "Invalid route position count");
        List<Vec3> points = new ArrayList<>(data.length / 3);
        for (int index = 0; index < data.length; index += 3) {
            double x = Double.longBitsToDouble(data[index]);
            double y = Double.longBitsToDouble(data[index + 1]);
            double z = Double.longBitsToDouble(data[index + 2]);
            require(Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z), "Invalid route position");
            points.add(new Vec3(x, y, z));
        }
        return points;
    }

    static ListTag compounds(CompoundTag tag, String name, int limit) throws IOException {
        require(tag.get(name) instanceof ListTag, "Missing route list");
        ListTag list = (ListTag) tag.get(name);
        require(list.size() <= limit && list.stream().allMatch(CompoundTag.class::isInstance),
                "Invalid route list");
        return list;
    }

    static void require(boolean valid, String detail) throws IOException {
        if (!valid) {
            throw new IOException(detail);
        }
    }
}
