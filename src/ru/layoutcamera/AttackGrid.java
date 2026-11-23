package ru.layoutcamera;

import com.google.gson.JsonElement;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

final class AttackGrid {
    private static final String TAG = "layoutcamera_attack_grid=";
    private static final int MAX_SIZE = 31;
    private static final int CELL_SIZE = 2;

    private final List<String> rows;
    private final List<Cell> cells;
    private final AABB localBounds;

    private AttackGrid(List<String> rows, List<Cell> cells) {
        this.rows = List.copyOf(rows);
        this.cells = List.copyOf(cells);
        AABB bounds = null;
        for (Cell cell : cells) {
            AABB square = new AABB(cell.x - 1, 0, cell.z - 1, cell.x + 1, 0, cell.z + 1);
            bounds = bounds == null ? square : bounds.minmax(square);
        }
        localBounds = bounds;
    }

    static AttackGrid read(JsonElement json) {
        if (json == null) {
            return null;
        }
        if (!json.isJsonArray()) {
            throw new IllegalArgumentException("attack_grid должен быть списком строк.");
        }
        List<String> rows = new ArrayList<>();
        for (JsonElement row : json.getAsJsonArray()) {
            if (!row.isJsonPrimitive() || !row.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException("Строки attack_grid должны быть текстом.");
            }
            rows.add(row.getAsString());
        }
        return parse(rows);
    }

    private static AttackGrid parse(List<String> rows) {
        if (rows.isEmpty() || rows.size() > MAX_SIZE || rows.getFirst().isEmpty()
            || rows.getFirst().length() > MAX_SIZE) {
            throw new IllegalArgumentException("Размер attack_grid должен быть от 1×1 до 31×31.");
        }
        int width = rows.getFirst().length();
        int originColumn = width / 2;
        int originRow = rows.size() / 2;
        if (width % 2 == 0 || rows.size() % 2 == 0 || rows.get(originRow).length() != width
            || "M@".indexOf(rows.get(originRow).charAt(originColumn)) < 0) {
            throw new IllegalArgumentException("В attack_grid нужна центральная клетка M или @ и нечётные размеры.");
        }
        List<Cell> cells = new ArrayList<>();
        for (int row = 0; row < rows.size(); row++) {
            String line = rows.get(row);
            if (line.length() != width) {
                throw new IllegalArgumentException("Все строки attack_grid должны быть одинаковой длины.");
            }
            for (int column = 0; column < width; column++) {
                char symbol = line.charAt(column);
                if (symbol == 'M' || symbol == '@') {
                    if (row != originRow || column != originColumn) {
                        throw new IllegalArgumentException("Символ M или @ допустим только в центре attack_grid.");
                    }
                } else if (symbol != '#' && symbol != '.') {
                    throw new IllegalArgumentException("В attack_grid допустимы только M, @, # и точка.");
                }
                if (symbol == '#' || symbol == '@') {
                    cells.add(new Cell((column - originColumn) * CELL_SIZE, (row - originRow) * CELL_SIZE));
                }
            }
        }
        return new AttackGrid(rows, cells);
    }

    static void assign(Mob mob, AttackGrid grid) {
        if (grid != null) {
            mob.addTag(TAG + String.join("/", grid.rows));
        }
    }

    static AttackGrid restore(Mob mob) {
        for (String tag : mob.entityTags()) {
            if (tag.startsWith(TAG)) {
                try {
                    return parse(List.of(tag.substring(TAG.length()).split("/", -1)));
                } catch (IllegalArgumentException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    static boolean isAssigned(Mob mob) {
        return mob.entityTags().stream().anyMatch(tag -> tag.startsWith(TAG));
    }

    boolean contains(Frame frame, Vec3 target) {
        int column = rows.getFirst().length() / 2 + (int) Math.floor((frame.right(target) + 1) / CELL_SIZE);
        int row = rows.size() / 2 + (int) Math.floor((frame.back(target) + 1) / CELL_SIZE);
        if (row < 0 || row >= rows.size() || column < 0 || column >= rows.get(row).length()) {
            return false;
        }
        char symbol = rows.get(row).charAt(column);
        return symbol == '#' || symbol == '@';
    }

    AABB searchBounds(Frame frame) {
        Vec3 a = frame.point(localBounds.minX, localBounds.minZ);
        Vec3 b = frame.point(localBounds.maxX, localBounds.maxZ);
        Vec3 c = frame.point(localBounds.minX, localBounds.maxZ);
        Vec3 d = frame.point(localBounds.maxX, localBounds.minZ);
        AABB horizontal = new AABB(a, b).minmax(new AABB(c, d));
        return new AABB(horizontal.minX - 0.001, Level.MIN_ENTITY_SPAWN_Y, horizontal.minZ - 0.001,
            horizontal.maxX + 0.001, Level.MAX_ENTITY_SPAWN_Y, horizontal.maxZ + 0.001);
    }

    boolean isEmpty() {
        return cells.isEmpty();
    }

    List<Cell> cells() {
        return cells;
    }

    record Frame(Vec3 center, double cos, double sin) {
        Frame(Vec3 center, float yaw) {
            this(center, axis(Math.cos(Math.toRadians(yaw))), axis(Math.sin(Math.toRadians(yaw))));
        }

        // At Minecraft yaw 0, forward is +Z and the right side is -X.
        Vec3 point(double right, double back) {
            return center.add(-right * cos + back * sin, 0, -right * sin - back * cos);
        }

        double right(Vec3 point) {
            return -(point.x - center.x) * cos - (point.z - center.z) * sin;
        }

        double back(Vec3 point) {
            return (point.x - center.x) * sin - (point.z - center.z) * cos;
        }

        private static double axis(double value) {
            return Math.abs(value) < 1e-12 ? 0 : value;
        }
    }

    record Cell(int x, int z) {}
}
