package ru.layoutcamera;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

final class AttackGridOverlay {
    private static final double OFFSET = 0.02;
    private static final double EPSILON = 1e-8;
    private static final double FACE_OFFSET = 0.004;
    private static final int EDGE_COLOR = 0x90ffb150;
    private static final GizmoStyle EDGE = GizmoStyle.stroke(EDGE_COLOR);
    private static final GizmoStyle STRIPE = GizmoStyle.fill(0xa0ff8a00);

    private AttackGridOverlay() {}

    static void draw(ClientLevel level, AttackGrid grid, Vec3 center, float yaw) {
        var frame = new AttackGrid.Frame(center.add(0, OFFSET, 0), yaw);
        Map<EdgeLine, List<SurfaceEdge>> edges = new HashMap<>();
        for (AttackGrid.Cell cell : grid.cells()) {
            drawCell(level, frame, cell.x() - 1, cell.z() - 1, edges);
        }
        drawRisers(frame, edges);
    }

    private static void drawCell(ClientLevel level, AttackGrid.Frame frame, double x, double z,
                                 Map<EdgeLine, List<SurfaceEdge>> edges) {
        List<Vec3> corners = List.of(frame.point(x, z), frame.point(x, z + 2), frame.point(x + 2, z + 2),
            frame.point(x + 2, z));
        double plane = frame.center().y;
        var bounds = new AABB(corners.get(0), corners.get(2)).minmax(new AABB(corners.get(1), corners.get(3)));
        List<Surface> surfaces = new ArrayList<>();
        boolean raised = false;
        for (int blockX = (int) Math.floor(bounds.minX); blockX < bounds.maxX - EPSILON; blockX++) {
            for (int blockZ = (int) Math.floor(bounds.minZ); blockZ < bounds.maxZ - EPSILON; blockZ++) {
                var tile = new Tile(blockX, blockZ, blockX + 1, blockZ + 1);
                AABB obstacle = coveringShape(level, blockX, blockZ, tile, plane);
                if (obstacle == null) {
                    surfaces.add(new Surface(tile, plane));
                } else {
                    raised = true;
                    split(level, blockX, blockZ, tile, plane, obstacle, surfaces);
                }
            }
        }

        List<List<Vec3>> stripes = new ArrayList<>(4);
        for (int stripe = -2; stripe < 2; stripe++) {
            stripes.add(stripe(frame, x, z, stripe + 0.2, stripe + 0.62));
        }
        if (!raised) {
            Gizmos.rect(corners.get(0), corners.get(1), corners.get(2), corners.get(3), EDGE);
            stripes.forEach(points -> fill(points, plane));
            for (int i = 0; i < corners.size(); i++) {
                addEdge(edges, corners.get(i), corners.get((i + 1) % corners.size()), plane);
            }
            return;
        }
        for (Surface surface : surfaces) {
            List<Vec3> piece = clip(corners, surface.tile());
            if (!hasArea(piece)) {
                continue;
            }
            for (int i = 0; i < piece.size(); i++) {
                Vec3 from = piece.get(i);
                Vec3 to = piece.get((i + 1) % piece.size());
                addEdge(edges, from, to, surface.height());
                if (cellEdge(frame, x, z, from, to)) {
                    Gizmos.line(atHeight(from, surface.height()), atHeight(to, surface.height()), EDGE_COLOR);
                }
            }
            for (List<Vec3> stripe : stripes) {
                fill(clip(stripe, surface.tile()), surface.height());
            }
        }
    }

    private static void addEdge(Map<EdgeLine, List<SurfaceEdge>> edges, Vec3 from, Vec3 to, double height) {
        boolean fixedX = Math.abs(from.x - to.x) < EPSILON;
        boolean fixedZ = Math.abs(from.z - to.z) < EPSILON;
        if (fixedX == fixedZ) {
            return;
        }
        double start = fixedX ? from.z : from.x;
        double end = fixedX ? to.z : to.x;
        double fixed = fixedX ? from.x : from.z;
        // The clipped polygons are clockwise in XZ, so the surface lies to the right of each edge.
        int side = (end > start ? 1 : -1) * (fixedX ? 1 : -1);
        var line = new EdgeLine(fixedX, Math.round(fixed / EPSILON));
        edges.computeIfAbsent(line, ignored -> new ArrayList<>()).add(
            new SurfaceEdge(Math.min(start, end), Math.max(start, end), height, side));
    }

    private static void drawRisers(AttackGrid.Frame frame, Map<EdgeLine, List<SurfaceEdge>> edges) {
        edges.forEach((line, segments) -> {
            segments.sort(Comparator.comparingDouble(SurfaceEdge::start));
            for (int i = 0; i < segments.size(); i++) {
                SurfaceEdge a = segments.get(i);
                for (int j = i + 1; j < segments.size() && segments.get(j).start() < a.end() - EPSILON; j++) {
                    List<Vec3> face = riser(line, a, segments.get(j));
                    if (!face.isEmpty()) {
                        drawRiserStripes(frame, face);
                    }
                }
            }
        });
    }

    static List<Vec3> riser(EdgeLine line, SurfaceEdge a, SurfaceEdge b) {
        double from = Math.max(a.start(), b.start());
        double to = Math.min(a.end(), b.end());
        if (a.side() == b.side() || to - from <= EPSILON || Math.abs(a.height() - b.height()) <= EPSILON) {
            return List.of();
        }
        SurfaceEdge low = a.height() < b.height() ? a : b;
        double high = Math.max(a.height(), b.height());
        double fixed = line.coordinate() * EPSILON + low.side() * FACE_OFFSET;
        return line.fixedX()
            ? List.of(new Vec3(fixed, low.height(), from), new Vec3(fixed, low.height(), to),
                new Vec3(fixed, high, to), new Vec3(fixed, high, from))
            : List.of(new Vec3(from, low.height(), fixed), new Vec3(to, low.height(), fixed),
                new Vec3(to, high, fixed), new Vec3(from, high, fixed));
    }

    private static void drawRiserStripes(AttackGrid.Frame frame, List<Vec3> face) {
        double low = face.getFirst().y;
        double rise = face.getLast().y - low;
        // One stripe repeat over the rise preserves alignment with the horizontal bands at both ends.
        ToDoubleFunction<Vec3> band = point -> frame.right(point) - frame.back(point) + (point.y - low) / rise;
        double min = face.stream().mapToDouble(band).min().orElseThrow();
        double max = face.stream().mapToDouble(band).max().orElseThrow();
        for (int i = (int) Math.floor(min); i <= (int) Math.floor(max); i++) {
            fillPolygon(clipEdge(clipEdge(face, band, i + 0.2, true), band, i + 0.62, false));
        }
    }

    private static List<Vec3> stripe(AttackGrid.Frame frame, double x, double z, double start, double end) {
        return List.of(
            frame.point(x + Math.max(start, 0), z + Math.max(-start, 0)),
            frame.point(x + 2 + Math.min(start, 0), z + 2 - Math.max(start, 0)),
            frame.point(x + 2 + Math.min(end, 0), z + 2 - Math.max(end, 0)),
            frame.point(x + Math.max(end, 0), z + Math.max(-end, 0)));
    }

    private static AABB coveringShape(ClientLevel level, int x, int z, Tile tile, double height) {
        int y = (int) Math.floor(height);
        // Collision boxes such as fences can extend above the block they belong to.
        for (int blockY = y; blockY >= y - 1; blockY--) {
            var pos = new BlockPos(x, blockY, z);
            var shape = level.getBlockState(pos).getCollisionShape(level, pos);
            for (AABB local : shape.toAabbs()) {
                AABB box = local.move(x, blockY, z);
                if (height >= box.minY && height < box.maxY && tile.overlaps(box)) {
                    return box;
                }
            }
        }
        return null;
    }

    private static void subdivide(ClientLevel level, int x, int z, Tile tile, double height, List<Surface> surfaces) {
        if (tile.maxX() - tile.minX() <= EPSILON || tile.maxZ() - tile.minZ() <= EPSILON) {
            return;
        }
        AABB shape = coveringShape(level, x, z, tile, height);
        if (shape == null) {
            surfaces.add(new Surface(tile, height));
        } else {
            split(level, x, z, tile, height, shape, surfaces);
        }
    }

    private static void split(ClientLevel level, int x, int z, Tile tile, double height, AABB shape,
                              List<Surface> surfaces) {
        double left = Math.max(tile.minX(), shape.minX);
        double front = Math.max(tile.minZ(), shape.minZ);
        double right = Math.min(tile.maxX(), shape.maxX);
        double back = Math.min(tile.maxZ(), shape.maxZ);
        subdivide(level, x, z, new Tile(tile.minX(), tile.minZ(), left, tile.maxZ()), height, surfaces);
        subdivide(level, x, z, new Tile(right, tile.minZ(), tile.maxX(), tile.maxZ()), height, surfaces);
        subdivide(level, x, z, new Tile(left, tile.minZ(), right, front), height, surfaces);
        subdivide(level, x, z, new Tile(left, back, right, tile.maxZ()), height, surfaces);
        subdivide(level, x, z, new Tile(left, front, right, back), shape.maxY + OFFSET, surfaces);
    }

    private static boolean cellEdge(AttackGrid.Frame frame, double x, double z, Vec3 from, Vec3 to) {
        double fromRight = frame.right(from);
        double toRight = frame.right(to);
        double fromBack = frame.back(from);
        double toBack = frame.back(to);
        return sameEdge(fromRight, toRight, x) || sameEdge(fromRight, toRight, x + 2)
            || sameEdge(fromBack, toBack, z) || sameEdge(fromBack, toBack, z + 2);
    }

    private static boolean sameEdge(double from, double to, double edge) {
        return Math.abs(from - edge) < EPSILON && Math.abs(to - edge) < EPSILON;
    }

    static List<Vec3> clip(List<Vec3> points, Tile tile) {
        points = clipEdge(points, point -> point.x, tile.minX(), true);
        points = clipEdge(points, point -> point.x, tile.maxX(), false);
        points = clipEdge(points, point -> point.z, tile.minZ(), true);
        return clipEdge(points, point -> point.z, tile.maxZ(), false);
    }

    private static List<Vec3> clipEdge(List<Vec3> input, ToDoubleFunction<Vec3> coordinate, double boundary,
                                       boolean keepAbove) {
        if (input.isEmpty()) {
            return input;
        }
        List<Vec3> output = new ArrayList<>();
        Vec3 from = input.getLast();
        double a = coordinate.applyAsDouble(from);
        for (Vec3 to : input) {
            double b = coordinate.applyAsDouble(to);
            boolean inside = keepAbove ? b >= boundary : b <= boundary;
            if (inside != (keepAbove ? a >= boundary : a <= boundary)) {
                output.add(from.lerp(to, (boundary - a) / (b - a)));
            }
            if (inside) {
                output.add(to);
            }
            from = to;
            a = b;
        }
        return output;
    }

    private static boolean hasArea(List<Vec3> points) {
        for (int i = 1; i + 1 < points.size(); i++) {
            if (triangleArea(points.getFirst(), points.get(i), points.get(i + 1)) > EPSILON) {
                return true;
            }
        }
        return false;
    }

    private static double triangleArea(Vec3 a, Vec3 b, Vec3 c) {
        return b.subtract(a).cross(c.subtract(a)).length();
    }

    private static void fill(List<Vec3> points, double height) {
        if (points.size() >= 3) {
            fillPolygon(points.stream().map(point -> atHeight(point, height)).toList());
        }
    }

    private static void fillPolygon(List<Vec3> points) {
        if (points.size() == 4 && hasArea(points)) {
            Gizmos.rect(points.get(0), points.get(1), points.get(2), points.get(3), STRIPE);
            return;
        }
        for (int i = 1; i + 1 < points.size(); i++) {
            if (triangleArea(points.getFirst(), points.get(i), points.get(i + 1)) > EPSILON) {
                Vec3 end = points.get(i + 1);
                Gizmos.rect(points.getFirst(), points.get(i), end, end, STRIPE);
            }
        }
    }

    private static Vec3 atHeight(Vec3 point, double height) {
        return new Vec3(point.x, height, point.z);
    }

    record Tile(double minX, double minZ, double maxX, double maxZ) {
        private boolean overlaps(AABB box) {
            return Math.min(maxX, box.maxX) - Math.max(minX, box.minX) > EPSILON
                && Math.min(maxZ, box.maxZ) - Math.max(minZ, box.minZ) > EPSILON;
        }
    }

    private record Surface(Tile tile, double height) {}

    record EdgeLine(boolean fixedX, long coordinate) {}

    record SurfaceEdge(double start, double end, double height, int side) {}
}
