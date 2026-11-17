package dev.waythread;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

final class RouteSearch {
    private static final int MAX_EXPANSIONS = 256;
    private static final long TICK_BUDGET_NS = 1_500_000L;
    private final WalkableTerrain terrain;
    private final BreadcrumbTrail.Segment segment;
    private final WalkableTerrain.Step goal;
    private final Map<BlockPos, Node> best = new HashMap<>();
    private final PriorityQueue<Node> frontier = new PriorityQueue<>(
            Comparator.comparingDouble(Node::score).thenComparingInt(Node::estimate));
    private int expansions;
    private List<Vec3> result;

    RouteSearch(WalkableTerrain terrain, BreadcrumbTrail.Segment segment) {
        this.terrain = terrain;
        this.segment = segment;
        WalkableTerrain.Step start = terrain.stepNear(segment.start());
        goal = terrain.stepNear(segment.end());
        if (start == null || goal == null || start.block().equals(goal.block())) {
            result = segment.samples();
        } else {
            add(new Node(start, null, 0, estimate(start.block())));
        }
    }

    List<Vec3> advance() {
        long started = System.nanoTime();
        while (result == null) {
            Node node = frontier.poll();
            if (node == null || expansions == MAX_EXPANSIONS) {
                result = segment.samples();
            } else if (best.get(node.step().block()) == node) {
                if (goal.block().equals(node.step().block())) {
                    result = followNodes(node);
                } else {
                    expansions++;
                    expand(node);
                }
            }
            if (System.nanoTime() - started >= TICK_BUDGET_NS) {
                break;
            }
        }
        return result;
    }

    private void expand(Node node) {
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos adjacent = node.step().block().relative(direction);
            for (int offset = 1; offset >= -3; offset--) {
                BlockPos candidate = adjacent.offset(0, offset, 0);
                WalkableTerrain.Step step = terrain.stepAt(candidate);
                if (step == null || !withinTrack(step.position())) {
                    continue;
                }
                double cost = node.cost() + 1 + Math.abs(step.y() - node.step().y()) * 0.35;
                Node prior = best.get(candidate);
                if ((prior == null || prior.cost() > cost) && terrain.canWalk(node.step(), step)) {
                    add(new Node(step, node, cost, estimate(candidate)));
                }
            }
        }
    }

    private boolean withinTrack(Vec3 position) {
        for (Vec3 sample : segment.samples()) {
            double x = position.x - sample.x;
            double z = position.z - sample.z;
            if (x * x + z * z <= 1.25 * 1.25 && Math.abs(position.y - sample.y) <= 1.15) {
                return true;
            }
        }
        return false;
    }

    private void add(Node node) {
        best.put(node.step().block(), node);
        frontier.add(node);
    }

    private int estimate(BlockPos pos) {
        return Math.abs(pos.getX() - goal.block().getX()) + Math.abs(pos.getZ() - goal.block().getZ());
    }

    private List<Vec3> followNodes(Node last) {
        List<Vec3> path = new ArrayList<>();
        path.add(segment.end());
        for (Node node = last; node.previous() != null; node = node.previous()) {
            Vec3 to = node.step().position();
            Vec3 from = node.previous().step().position();
            path.add(to);
            path.add(new Vec3((from.x + to.x) * 0.5, Math.max(from.y, to.y), (from.z + to.z) * 0.5));
        }
        path.add(segment.start());
        Collections.reverse(path);
        return List.copyOf(path);
    }

    private record Node(WalkableTerrain.Step step, Node previous, double cost, int estimate) {
        double score() {
            return cost + estimate;
        }
    }
}
