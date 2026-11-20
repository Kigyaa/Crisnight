package ru.layoutcamera;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import ru.layoutcamera.ZoneSelection.ZoneColor;

final class LocalLevels {
    private static final String LEVEL_TAG = "layoutcamera_level=";
    private static final SpawnZones.Config DEFAULT_SPAWN =
        new SpawnZones.Config(List.of("minecraft:zombie"), 40, 10, 50, null);
    private static final Set<String> SPAWN_FIELDS =
        Set.of("order", "interval_ticks", "total_enemies", "finish_search_radius", "attack_zones");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private LocalLevels() {}

    static Map<String, LayoutLevel> load(Path path) throws IOException {
        if (Files.notExists(path)) {
            return Map.of();
        }
        try (var reader = Files.newBufferedReader(path)) {
            Map<String, LayoutLevel> levels = new LinkedHashMap<>();
            Set<UUID> ids = new LinkedHashSet<>();
            for (var entry : JsonParser.parseReader(reader).getAsJsonObject().entrySet()) {
                String name = entry.getKey();
                checkName(name);
                LayoutLevel layout = readLayout(entry.getValue().getAsJsonObject());
                if (!ids.add(layout.id())
                    || levels.values().stream().anyMatch(level -> level.bounds().intersects(layout.bounds()))) {
                    throw new IllegalArgumentException("Повторяющийся ID или пересечение уровней: " + name);
                }
                levels.put(name, layout);
            }
            return orderedCopy(levels);
        } catch (RuntimeException exception) {
            throw new IOException("Проверь " + path.getFileName() + ": " + exception.getMessage(), exception);
        }
    }

    static void save(Path path, Map<String, LayoutLevel> levels) throws IOException {
        JsonObject root = new JsonObject();
        levels.forEach((name, level) -> root.add(name, writeLayout(level)));
        writeJson(path, root);
    }

    static LayoutLevel readLayout(JsonObject json) {
        UUID id = UUID.fromString(json.get("id").getAsString());
        AABB bounds = readBounds(json.getAsJsonArray("bounds"));
        Map<UUID, Zone> zones = new LinkedHashMap<>();
        for (var zoneEntry : json.getAsJsonObject("zones").entrySet()) {
            UUID zoneId = UUID.fromString(zoneEntry.getKey());
            JsonObject item = zoneEntry.getValue().getAsJsonObject();
            ZoneColor color = ZoneColor.valueOf(item.get("type").getAsString().toUpperCase(Locale.ROOT));
            AABB area = readBounds(item.getAsJsonArray("bounds"));
            if (zones.containsKey(zoneId) || !contains(bounds, area)) {
                throw new IllegalArgumentException("Повторяющийся ID или зона за границей уровня: " + zoneId);
            }
            ZoneSettings settings = ZoneSettings.read(color, item.getAsJsonObject("settings"));
            zones.put(zoneId, new Zone(zoneId, area, color, settings));
        }
        return new LayoutLevel(id, bounds, zones);
    }

    static JsonObject writeLayout(LayoutLevel level) {
        JsonObject json = new JsonObject();
        json.addProperty("id", level.id().toString());
        json.add("bounds", writeBounds(level.bounds()));
        JsonObject zones = new JsonObject();
        level.zones().values().forEach(zone -> {
            JsonObject item = new JsonObject();
            item.addProperty("type", zone.color().name().toLowerCase(Locale.ROOT));
            item.add("bounds", writeBounds(zone.bounds()));
            item.add("settings", zone.settings().json());
            zones.add(zone.id().toString(), item);
        });
        json.add("zones", zones);
        return json;
    }

    static void writeJson(Path path, JsonObject root) throws IOException {
        Files.createDirectories(path.getParent());
        Path temp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(temp, GSON.toJson(root) + System.lineSeparator());
        Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    static void checkName(String name) {
        if (!name.matches("[a-z0-9_-]{1,32}")) {
            throw new IllegalArgumentException("Имя уровня: от 1 до 32 символов a-z, 0-9, _ или -.");
        }
    }

    static boolean contains(AABB outer, AABB inner) {
        return outer.minX <= inner.minX && outer.minY <= inner.minY && outer.minZ <= inner.minZ
            && outer.maxX >= inner.maxX && outer.maxY >= inner.maxY && outer.maxZ >= inner.maxZ;
    }

    private static AABB readBounds(JsonArray bounds) {
        if (bounds == null || bounds.size() != 6) {
            throw new IllegalArgumentException("Для границ нужно 6 координат.");
        }
        int[] positions = new int[6];
        for (int i = 0; i < positions.length; i++) {
            if (!bounds.get(i).isJsonPrimitive() || !bounds.get(i).getAsJsonPrimitive().isNumber()) {
                throw new IllegalArgumentException("Координаты границ должны быть целыми числами.");
            }
            positions[i] = bounds.get(i).getAsBigDecimal().intValueExact();
        }
        if (positions[0] >= positions[3] || positions[1] >= positions[4] || positions[2] >= positions[5]) {
            throw new IllegalArgumentException("Границы должны образовывать непустой объём.");
        }
        return new AABB(positions[0], positions[1], positions[2], positions[3], positions[4], positions[5]);
    }

    private static JsonArray writeBounds(AABB box) {
        JsonArray values = new JsonArray();
        for (double coordinate : new double[] {box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ}) {
            values.add((int) coordinate);
        }
        return values;
    }

    static boolean isNear(AABB box, Vec3 position) {
        return isNear(box, position, 10);
    }

    static boolean isNear(AABB box, Vec3 position, double distance) {
        double x = position.x - Math.clamp(position.x, box.minX, box.maxX);
        double y = position.y - Math.clamp(position.y, box.minY, box.maxY);
        double z = position.z - Math.clamp(position.z, box.minZ, box.maxZ);
        return x * x + y * y + z * z <= distance * distance;
    }

    static void mark(Mob mob, UUID level) {
        if (level != null) {
            mob.addTag(LEVEL_TAG + level);
        }
    }

    static boolean belongsTo(Mob mob, UUID level) {
        return level != null ? mob.entityTags().contains(LEVEL_TAG + level) : scopeOf(mob) == null;
    }

    static boolean sameScope(Mob first, Mob second) {
        return Objects.equals(scopeOf(first), scopeOf(second));
    }

    private static String scopeOf(Mob mob) {
        for (String tag : mob.entityTags()) {
            if (tag.startsWith(LEVEL_TAG)) {
                return tag;
            }
        }
        return null;
    }

    static <K, V> Map<K, V> orderedCopy(Map<K, V> map) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(map));
    }

    record LayoutLevel(UUID id, AABB bounds, Map<UUID, Zone> zones) {
        LayoutLevel {
            zones = orderedCopy(zones);
        }
    }

    record Zone(UUID id, AABB bounds, ZoneColor color, ZoneSettings settings) {}

    record ZoneSettings(boolean enabled, Set<Identifier> allowedMobs, SpawnZones.Config spawn, JsonObject json) {
        static ZoneSettings read(ZoneColor color, JsonObject json) {
            for (String field : json.keySet()) {
                if (!field.equals("enabled") && !(color == ZoneColor.RED && SPAWN_FIELDS.contains(field))
                    && !((color == ZoneColor.BLUE || color == ZoneColor.GREEN) && field.equals("allowed_mobs"))) {
                    throw new IllegalArgumentException("Неизвестная настройка зоны: " + field);
                }
            }
            var enabled = json.get("enabled");
            if (enabled != null && (!enabled.isJsonPrimitive() || !enabled.getAsJsonPrimitive().isBoolean())) {
                throw new IllegalArgumentException("В enabled нужно true или false.");
            }
            Set<Identifier> allowed = null;
            if (json.has("allowed_mobs")) {
                if (!json.get("allowed_mobs").isJsonArray()) {
                    throw new IllegalArgumentException("В allowed_mobs нужен список ID из mobs.json.");
                }
                Set<Identifier> ids = new LinkedHashSet<>();
                for (var item : json.getAsJsonArray("allowed_mobs")) {
                    Identifier id = item.isJsonPrimitive() && item.getAsJsonPrimitive().isString()
                        ? PetConfig.petId(item.getAsString()) : null;
                    if (id == null) {
                        throw new IllegalArgumentException("Некорректный ID в allowed_mobs: " + item);
                    }
                    ids.add(id);
                }
                allowed = Set.copyOf(ids);
            }
            SpawnZones.Config spawn = color == ZoneColor.RED ? SpawnZones.Config.fromJson(json, DEFAULT_SPAWN) : null;
            return new ZoneSettings(enabled == null || enabled.getAsBoolean(), allowed, spawn, json.deepCopy());
        }

        boolean permits(Identifier id) {
            return enabled && (allowedMobs == null || allowedMobs.contains(id));
        }
    }
}
