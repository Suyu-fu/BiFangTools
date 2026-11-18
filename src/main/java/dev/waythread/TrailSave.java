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
import net.minecraft.nbt.Tag;
import net.minecraft.world.phys.Vec3;

record TrailSave(DimensionalTrail trail, Waythread.Mode mode, int returnStartPointCount) {
    private static final int FORMAT_VERSION = 1;
    private static final int MAX_NBT_BYTES = 32 * 1024 * 1024;

    static Path fileFor(Path clientConfig, Path worldDirectory, long worldSeed) throws IOException {
        String world = worldDirectory.toRealPath().toString();
        String key = Hashing.sha256().hashString(world + '\n' + worldSeed, StandardCharsets.UTF_8).toString();
        return clientConfig.resolve("bifangtools").resolve("waythread").resolve(key + ".dat");
    }

    static TrailSave read(Path file) throws IOException {
        if (Files.notExists(file)) {
            return null;
        }
        CompoundTag tag = NbtIo.readCompressed(file, NbtAccounter.create(MAX_NBT_BYTES));
        require(tag.getInt("Version") == FORMAT_VERSION, "Unsupported route save version");
        Waythread.Mode mode = Waythread.Mode.valueOf(tag.getString("Mode"));
        DimensionalTrail trail = DimensionalTrail.readSave(tag.getCompound("Route"));
        int startingPoints = tag.getInt("ReturnStart");
        int points = trail.pointCount();
        require((mode == Waythread.Mode.ARRIVED) == (points == 0), "Route count does not match its mode");
        if (mode == Waythread.Mode.RETURNING) {
            require(startingPoints >= points && startingPoints <= WaythreadConfig.MAX_RECORDED_POINT_LIMIT,
                    "Invalid return progress");
        } else {
            require(startingPoints == 0 && !trail.waitingToRejoin(), "Unexpected return state");
        }
        return new TrailSave(trail, mode, startingPoints);
    }

    void write(Path file) throws IOException {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Version", FORMAT_VERSION);
        tag.putString("Mode", mode.name());
        tag.putInt("ReturnStart", returnStartPointCount);
        tag.put("Route", trail.writeSave());
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
        require(tag.contains(name, Tag.TAG_LONG_ARRAY), "Missing route position array");
        long[] data = tag.getLongArray(name);
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
        require(list.size() <= limit && (list.isEmpty() || list.getElementType() == Tag.TAG_COMPOUND),
                "Invalid route list");
        return list;
    }

    static void require(boolean valid, String detail) throws IOException {
        if (!valid) {
            throw new IOException(detail);
        }
    }
}
