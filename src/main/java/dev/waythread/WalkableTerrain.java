package dev.waythread;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

final class WalkableTerrain {
    private static final double HALF_WIDTH = 0.3;
    private static final double PLAYER_HEIGHT = 1.8;
    private static final double EPSILON = 1.0e-5;

    private final CollisionGetter level;
    private final Predicate<BlockPos> loaded;
    private final Map<BlockPos, Optional<Step>> steps = new HashMap<>();

    WalkableTerrain(ClientLevel level) {
        this(level, pos -> level.getChunkSource().getChunk(
                SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()),
                ChunkStatus.FULL, false) != null);
    }

    WalkableTerrain(CollisionGetter level, Predicate<BlockPos> loaded) {
        this.level = level;
        this.loaded = loaded;
    }

    Step stepNear(Vec3 position) {
        BlockPos feet = BlockPos.containing(position);
        Step nearest = null;
        double bestDistance = 0.95 * 0.95;
        for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-1, -1, -1), feet.offset(1, 0, 1))) {
            Step step = stepAt(pos.immutable());
            if (step != null && Math.abs(position.y - step.y()) <= 0.45) {
                double distance = step.position().distanceToSqr(position);
                if (distance < bestDistance) {
                    nearest = step;
                    bestDistance = distance;
                }
            }
        }
        return nearest;
    }

    Step stepAt(BlockPos support) {
        if (level.isOutsideBuildHeight(support) || !loaded.test(support)
                || !level.getWorldBorder().isWithinBounds(support)) {
            return null;
        }
        return steps.computeIfAbsent(support, pos -> Optional.ofNullable(findStep(pos))).orElse(null);
    }

    private Step findStep(BlockPos support) {
        BlockState state = level.getBlockState(support);
        if (!isSafe(state)) {
            return null;
        }
        VoxelShape shape = state.getCollisionShape(level, support);
        if (shape.isEmpty() || shape.max(Direction.Axis.Y) > 1 + EPSILON) {
            return null;
        }
        double top = 0;
        for (AABB part : shape.toAabbs()) {
            if (part.minX < 0.5 + HALF_WIDTH && part.maxX > 0.5 - HALF_WIDTH
                    && part.minZ < 0.5 + HALF_WIDTH && part.maxZ > 0.5 - HALF_WIDTH) {
                top = Math.max(top, part.maxY);
            }
        }
        Step step = new Step(support, support.getY() + top);
        return top > 0 && isClear(body(step, step.y())) ? step : null;
    }

    boolean canWalk(Step from, Step to) {
        double rise = to.y() - from.y();
        if (rise > 1 + EPSILON || rise < -3 - EPSILON) {
            return false;
        }
        double crossing = Math.max(from.y(), to.y()) + (rise > 0.6 ? 0.2 : 0);
        AABB fromCrossing = body(from, crossing);
        AABB toCrossing = body(to, crossing);
        return (crossing == from.y() || isClear(body(from, from.y()).minmax(fromCrossing)))
                && isClear(fromCrossing.minmax(toCrossing))
                && (crossing == to.y() || isClear(toCrossing.minmax(body(to, to.y()))));
    }

    boolean canJoin(Vec3 from, Vec3 to) {
        int checks = Math.max(1, (int) Math.ceil(from.distanceTo(to) * 4));
        AABB previous = body(from, from.y);
        for (int i = 0; i <= checks; i++) {
            Vec3 position = from.lerp(to, (double) i / checks);
            AABB body = body(position, position.y);
            AABB feet = new AABB(body.minX, position.y - 0.35, body.minZ, body.maxX, position.y + EPSILON, body.maxZ);
            if (!isClear(previous.minmax(body)) || !level.getBlockCollisions(null, feet).iterator().hasNext()) {
                return false;
            }
            previous = body;
        }
        return true;
    }

    private boolean isClear(AABB volume) {
        BlockPos min = BlockPos.containing(volume.minX, volume.minY, volume.minZ);
        BlockPos max = BlockPos.containing(volume.maxX - EPSILON, volume.maxY - EPSILON, volume.maxZ - EPSILON);
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            if (!loaded.test(pos) || !isSafe(level.getBlockState(pos))) {
                return false;
            }
        }
        return !level.getBlockCollisions(null, volume).iterator().hasNext();
    }

    private static boolean isSafe(BlockState state) {
        return state.getFluidState().isEmpty() && !CampfireBlock.isLitCampfire(state)
                && !state.is(Blocks.CACTUS) && !state.is(Blocks.MAGMA_BLOCK) && !state.is(Blocks.FIRE)
                && !state.is(Blocks.SOUL_FIRE) && !state.is(Blocks.POWDER_SNOW)
                && !state.is(Blocks.SWEET_BERRY_BUSH) && !state.is(Blocks.WITHER_ROSE)
                && !state.is(Blocks.POINTED_DRIPSTONE);
    }

    private static AABB body(Step step, double height) {
        return body(step.position(), height);
    }

    private static AABB body(Vec3 center, double height) {
        return new AABB(center.x - HALF_WIDTH, height + EPSILON, center.z - HALF_WIDTH,
                center.x + HALF_WIDTH, height + PLAYER_HEIGHT, center.z + HALF_WIDTH);
    }

    record Step(BlockPos block, double y) {
        Vec3 position() {
            return new Vec3(block.getX() + 0.5, y, block.getZ() + 0.5);
        }
    }
}
