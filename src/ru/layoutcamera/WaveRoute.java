package ru.layoutcamera;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

final class WaveRoute {
    static final int MAX_EDIT_POINTS = 128;
    static final int MAX_PATH_POINTS = 1024;
    private static final int MAX_SEARCH_NODES = 4096;
    private static final double DEVIATION = 0.6;
    private static final List<Direction> HORIZONTAL = List.of(Direction.NORTH, Direction.SOUTH, Direction.WEST,
        Direction.EAST);

    private WaveRoute() {}

    static Vec3 center(BlockPos point) {
        return new Vec3(point.getX(), point.getY(), point.getZ());
    }

    static boolean deviated(Vec3 position, BlockPos start, BlockPos end) {
        Vec3 from = center(start);
        Vec3 to = center(end);
        Vec3 direction = to.subtract(from);
        double horizontalLength = direction.horizontalDistanceSqr();
        double fraction = horizontalLength == 0 ? 0
            : Math.clamp(progress(position, from, direction) / horizontalLength, 0, 1);
        double dx = position.x - (from.x + direction.x * fraction);
        double dz = position.z - (from.z + direction.z * fraction);
        double floor = Math.min(from.y, to.y);
        double ceiling = Math.max(from.y, to.y);
        double dy = position.y - Math.clamp(position.y, floor, ceiling);
        return dx * dx + dy * dy + dz * dz > DEVIATION * DEVIATION;
    }

    static boolean passed(Vec3 position, BlockPos start, BlockPos end) {
        Vec3 from = center(start);
        Vec3 to = center(end);
        Vec3 direction = to.subtract(from);
        double horizontalLength = direction.horizontalDistanceSqr();
        return horizontalLength > 0 && Math.abs(position.y - to.y) <= 0.25
            && progress(position, from, direction) >= horizontalLength;
    }

    private static double progress(Vec3 position, Vec3 from, Vec3 direction) {
        return (position.x - from.x) * direction.x + (position.z - from.z) * direction.z;
    }

    static BlockPos snap(Vec3 hit, int feetY) {
        return new BlockPos((int) Math.round(hit.x), feetY, (int) Math.round(hit.z));
    }

    static boolean insideLevel(AABB bounds, BlockPos point) {
        return point.getX() > bounds.minX && point.getX() < bounds.maxX && point.getZ() > bounds.minZ
            && point.getZ() < bounds.maxZ && point.getY() > bounds.minY && point.getY() <= bounds.maxY;
    }

    static boolean onZone(AABB zone, BlockPos point) {
        return point.getY() == zone.maxY && insideLevel(zone, point);
    }

    static boolean supported(Level level, Vec3 center) {
        int minX = (int) Math.floor(center.x - 0.35);
        int maxX = (int) Math.floor(center.x + 0.35);
        int minZ = (int) Math.floor(center.z - 0.35);
        int maxZ = (int) Math.floor(center.z + 0.35);
        int floorY = (int) center.y - 1;
        for (BlockPos floor : BlockPos.betweenClosed(minX, floorY, minZ, maxX, floorY, maxZ)) {
            if (!level.hasChunkAt(floor) || !level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP)) {
                return false;
            }
        }
        return level.noBlockCollision(null, new AABB(center.x - 0.35, center.y, center.z - 0.35,
            center.x + 0.35, center.y + 1.8, center.z + 0.35));
    }

    static boolean isSegmentWalkable(Level level, BlockPos start, BlockPos end) {
        Vec3 from = center(start);
        Vec3 to = center(end);
        int samples = (int) Math.ceil(from.distanceTo(to) * 4);
        if (start.getY() != end.getY()) {
            if (start.getY() - end.getY() != 1 || horizontalDistance(start, end) > 2
                || (start.getX() != end.getX() && start.getZ() != end.getZ())) {
                return false;
            }
            for (int i = 1; i <= samples; i++) {
                Vec3 above = from.lerp(to, (double) i / samples);
                if (!level.noBlockCollision(null, new AABB(above.x - 0.35, from.y, above.z - 0.35,
                    above.x + 0.35, from.y + 1.8, above.z + 0.35))) {
                    return false;
                }
            }
            return true;
        }
        for (int i = 1; i < samples; i++) {
            if (!supported(level, from.lerp(to, (double) i / samples))) {
                return false;
            }
        }
        return true;
    }

    static List<BlockPos> automatic(Level level, BlockPos start, BlockPos finish, AABB bounds) {
        if (start.getY() < finish.getY() || !supported(level, center(start)) || !supported(level, center(finish))) {
            return List.of();
        }
        if (start.getY() == finish.getY() && (start.getX() == finish.getX() || start.getZ() == finish.getZ())
            && isSegmentWalkable(level, start, finish)) {
            return start.equals(finish) ? List.of(start) : List.of(start, finish);
        }
        Map<BlockPos, Boolean> standable = new HashMap<>();
        return search(start, finish, point -> (bounds == null || insideLevel(bounds, point))
            && standable.computeIfAbsent(point, candidate -> supported(level, center(candidate))),
            (from, to) -> isSegmentWalkable(level, from, to));
    }

    static List<BlockPos> search(BlockPos start, BlockPos finish, Predicate<BlockPos> standable,
                                 BiPredicate<BlockPos, BlockPos> segment) {
        var open = new PriorityQueue<Node>(Comparator.comparingInt(Node::score)
            .thenComparing(Comparator.comparingInt(Node::steps).reversed()));
        Map<BlockPos, BlockPos> parent = new HashMap<>();
        Map<BlockPos, Integer> distance = new HashMap<>();
        open.add(new Node(start, 0, horizontalDistance(start, finish)));
        distance.put(start, 0);
        while (!open.isEmpty() && distance.size() <= MAX_SEARCH_NODES) {
            Node node = open.remove();
            BlockPos point = node.point();
            if (node.steps() != distance.get(point)) {
                continue;
            }
            if (point.equals(finish)) {
                return simplify(parent, finish);
            }
            for (Direction direction : HORIZONTAL) {
                BlockPos next = point.relative(direction);
                if (standable.test(next)) {
                    if (segment.test(point, next)) {
                        offer(open, parent, distance, node, next, finish);
                    }
                } else if (next.getY() > finish.getY()) {
                    BlockPos lower = next.relative(direction).below();
                    if (standable.test(lower) && segment.test(point, lower)) {
                        offer(open, parent, distance, node, lower, finish);
                    }
                }
            }
        }
        return List.of();
    }

    private static void offer(PriorityQueue<Node> open, Map<BlockPos, BlockPos> parent, Map<BlockPos, Integer> distance,
                              Node current, BlockPos next, BlockPos finish) {
        int steps = current.steps() + 1;
        if (steps < distance.getOrDefault(next, Integer.MAX_VALUE)) {
            distance.put(next, steps);
            parent.put(next, current.point());
            open.add(new Node(next, steps, steps + horizontalDistance(next, finish)));
        }
    }

    private static int horizontalDistance(BlockPos a, BlockPos b) {
        return Math.abs(a.getX() - b.getX()) + Math.abs(a.getZ() - b.getZ());
    }

    private static List<BlockPos> simplify(Map<BlockPos, BlockPos> parents, BlockPos finish) {
        List<BlockPos> points = new ArrayList<>();
        BlockPos point = finish;
        points.add(point);
        BlockPos direction = null;
        while (parents.containsKey(point)) {
            BlockPos previous = parents.get(point);
            BlockPos step = point.subtract(previous);
            if (direction != null && (!step.equals(direction) || step.getY() != 0)) {
                points.add(point);
            }
            direction = step;
            point = previous;
        }
        points.add(point);
        if (points.size() > MAX_PATH_POINTS) {
            return List.of();
        }
        Collections.reverse(points);
        return List.copyOf(points);
    }

    private record Node(BlockPos point, int steps, int score) {}
}
