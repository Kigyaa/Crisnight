package ru.layoutcamera;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import ru.layoutcamera.LocalLevels.LayoutLevel;
import ru.layoutcamera.LocalLevels.Zone;
import ru.layoutcamera.LocalLevels.ZoneSettings;
import ru.layoutcamera.ZoneSelection.ZoneColor;

final class StageFiles {
    static final Path ROOT = PetConfig.PROJECT_DIRECTORY.resolve("Stage");
    private static final int MAX_VOLUME = 262_144;
    private static final int FORMAT_VERSION = 1;

    private StageFiles() {}

    static Path directory(String name) {
        LocalLevels.checkName(name);
        if (name.matches("con|prn|aux|nul|com[0-9]|lpt[0-9]")) {
            throw new IllegalArgumentException("Выбери другое имя уровня: " + name);
        }
        return ROOT.resolve(name);
    }

    static Snapshot capture(ServerLevel level, LayoutLevel layout, List<Placement> placements) {
        AABB bounds = layout.bounds();
        int volume = volume(bounds);
        if (!level.isInWorldBounds(min(bounds)) || !level.isInWorldBounds(max(bounds))) {
            throw new IllegalArgumentException("Граница уровня выходит за пределы мира.");
        }
        int[] blocks = new int[volume];
        Map<BlockState, Integer> states = new LinkedHashMap<>();
        int index = 0;
        for (BlockPos position : positions(bounds)) {
            BlockState state = level.getBlockState(position);
            blocks[index++] = states.computeIfAbsent(state, ignored -> states.size());
        }
        return new Snapshot(relative(layout), List.copyOf(states.keySet()), blocks, relativePlacements(layout, placements));
    }

    static Snapshot updateFromDraft(Snapshot snapshot, LayoutLevel draft, List<Placement> placements) {
        LayoutLevel saved = snapshot.layout();
        if (draft == null || !saved.id().equals(draft.id())) {
            return snapshot;
        }
        LayoutLevel relative = relative(draft);
        if (!saved.bounds().equals(relative.bounds())) {
            return snapshot;
        }
        Map<UUID, Zone> zones = new LinkedHashMap<>(saved.zones());
        for (Zone zone : relative.zones().values()) {
            Zone previous = zones.get(zone.id());
            if (previous == null) {
                previous = zones.values().stream().filter(existing ->
                    existing.color() == zone.color() && existing.bounds().equals(zone.bounds())).findFirst().orElse(null);
            }
            if (previous == null) {
                zones.put(zone.id(), zone);
            } else if (previous.color() == ZoneColor.RED && previous.bounds().equals(zone.bounds())
                && !previous.route().equals(zone.route())) {
                zones.put(previous.id(), new Zone(previous.id(), previous.bounds(), previous.color(),
                    previous.settings(), zone.route()));
            }
        }
        List<Placement> updatedPlacements = relativePlacements(draft, placements);
        return zones.equals(saved.zones()) && updatedPlacements.equals(snapshot.placements()) ? snapshot
            : new Snapshot(new LayoutLevel(saved.id(), saved.bounds(), zones), snapshot.palette(), snapshot.blocks(),
                updatedPlacements);
    }

    private static LayoutLevel relative(LayoutLevel layout) {
        AABB bounds = layout.bounds();
        return translate(layout, new Vec3(-bounds.minX, -bounds.minY, -bounds.minZ), layout.id());
    }

    private static List<Placement> relativePlacements(LayoutLevel layout, List<Placement> placements) {
        AABB bounds = layout.bounds();
        Vec3 offset = new Vec3(-bounds.minX, -bounds.minY, -bounds.minZ);
        return placements.stream().map(placement -> placement.move(offset)).toList();
    }

    static UUID sourceId(Path directory) throws IOException {
        Path file = directory.resolve("stage.nbt");
        if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        try {
            String zones = readNbt(file).getString("zones").orElseThrow();
            return UUID.fromString(JsonParser.parseString(zones).getAsJsonObject().get("id").getAsString());
        } catch (RuntimeException exception) {
            throw new IOException("Не удалось прочитать ID исходной области уровня.", exception);
        }
    }

    static void save(Path directory, Snapshot snapshot) throws IOException {
        Files.createDirectories(directory);
        Path spawnFile = directory.resolve("spawn.json");
        JsonObject spawns = readSpawns(spawnFile);
        snapshot.layout().zones().values().forEach(zone -> {
            if (zone.color() == ZoneColor.RED && !spawns.has(zone.id().toString())) {
                spawns.add(zone.id().toString(), spawnSettings(zone.settings()));
            }
        });
        LocalLevels.writeJson(spawnFile, spawns);

        CompoundTag data = new CompoundTag();
        data.putInt("format", FORMAT_VERSION);
        data.putString("zones", LocalLevels.writeLayout(snapshot.layout()).toString());
        ListTag palette = new ListTag();
        snapshot.palette().forEach(state -> palette.add(NbtUtils.writeBlockState(state)));
        data.put("palette", palette);
        data.putIntArray("blocks", snapshot.blocks());
        ListTag placements = new ListTag();
        snapshot.placements().forEach(placement -> placements.add(placement.encode()));
        data.put("placements", placements);
        writeNbt(directory.resolve("stage.nbt"), data);
    }

    static Snapshot load(Path directory) throws IOException {
        Path file = directory.resolve("stage.nbt");
        try {
            CompoundTag data = readNbt(file);
            if (data.getIntOr("format", 0) != FORMAT_VERSION) {
                throw new IllegalArgumentException("Неизвестный формат сохранения.");
            }
            JsonObject geometry = JsonParser.parseString(data.getString("zones").orElseThrow()).getAsJsonObject();
            JsonObject spawns = readSpawns(directory.resolve("spawn.json"));
            geometry.getAsJsonObject("zones").entrySet().forEach(entry -> {
                JsonObject zone = entry.getValue().getAsJsonObject();
                if ("red".equals(zone.get("type").getAsString()) && spawns.has(entry.getKey())) {
                    zone.add("settings", spawns.getAsJsonObject(entry.getKey()));
                }
            });
            LayoutLevel layout = LocalLevels.readLayout(geometry);
            AABB bounds = layout.bounds();
            if (!min(bounds).equals(BlockPos.ZERO)) {
                throw new IllegalArgumentException("Начало координат сохранения должно быть 0 0 0.");
            }
            int[] blocks = data.getIntArray("blocks").orElseThrow();
            if (blocks.length != volume(bounds)) {
                throw new IllegalArgumentException("Число блоков не совпадает с размером уровня.");
            }
            ListTag savedPalette = data.getList("palette").orElseThrow();
            List<BlockState> palette = new ArrayList<>();
            for (int index = 0; index < savedPalette.size(); index++) {
                palette.add(NbtUtils.readBlockState(BuiltInRegistries.BLOCK, savedPalette.getCompound(index).orElseThrow()));
            }
            for (int state : blocks) {
                if (state < 0 || state >= palette.size()) {
                    throw new IllegalArgumentException("Неизвестный блок в сохранении.");
                }
            }
            List<Placement> placements = new ArrayList<>();
            for (var tag : data.getListOrEmpty("placements")) {
                if (!(tag instanceof CompoundTag item)) {
                    throw new IllegalArgumentException("Некорректная запись расстановки.");
                }
                Placement placement = Placement.decode(item);
                if (ZoneSelection.placementMarkerColor(layout, placement.center(), placement.zone()) != placement.zone()) {
                    throw new IllegalArgumentException("Точка моба находится за пределами своей зоны.");
                }
                placements.add(placement);
            }
            return new Snapshot(layout, List.copyOf(palette), blocks, List.copyOf(placements));
        } catch (RuntimeException exception) {
            throw new IOException("Проверь сохранение " + directory.getFileName() + ": " + exception.getMessage(), exception);
        }
    }

    private static JsonObject readSpawns(Path path) throws IOException {
        if (Files.notExists(path)) {
            return new JsonObject();
        }
        try (var reader = Files.newBufferedReader(path)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (RuntimeException exception) {
            throw new IOException("Проверь " + path + ": " + exception.getMessage(), exception);
        }
    }

    private static JsonObject spawnSettings(ZoneSettings settings) {
        var config = settings.spawn();
        JsonObject json = new JsonObject();
        json.addProperty("enabled", settings.enabled());
        JsonArray order = new JsonArray();
        config.order().forEach(order::add);
        json.add("order", order);
        json.addProperty("interval_ticks", config.intervalTicks());
        json.addProperty("total_enemies", config.total());
        json.addProperty("finish_search_radius", config.finishSearchRadius());
        JsonArray attackZones = new JsonArray();
        for (ZoneColor color : ZoneColor.values()) {
            if (config.attackZones() == null || config.attackZones().contains(color)) {
                attackZones.add(color.name().toLowerCase(Locale.ROOT));
            }
        }
        json.add("attack_zones", attackZones);
        return json;
    }

    static LayoutLevel translate(LayoutLevel layout, Vec3 offset, UUID id) {
        Map<UUID, Zone> zones = new LinkedHashMap<>();
        BlockPos blocks = BlockPos.containing(offset);
        layout.zones().forEach((zoneId, zone) -> zones.put(zoneId,
            new Zone(zoneId, zone.bounds().move(offset), zone.color(), zone.settings(),
                zone.route().stream().map(point -> point.offset(blocks)).toList())));
        return new LayoutLevel(id, layout.bounds().move(offset), zones);
    }

    static int volume(AABB bounds) {
        if (bounds.getXsize() > 128 || bounds.getZsize() > 128) {
            throw new IllegalArgumentException("Ширина и длина уровня ограничены 128 блоками.");
        }
        double volume = bounds.getXsize() * bounds.getYsize() * bounds.getZsize();
        if (volume <= 0 || volume > MAX_VOLUME) {
            throw new IllegalArgumentException("Размер уровня должен быть от 1 до " + MAX_VOLUME + " блоков объёма.");
        }
        return (int) volume;
    }

    static Iterable<BlockPos> positions(AABB bounds) {
        return BlockPos.betweenClosed(min(bounds), max(bounds));
    }

    static BlockPos min(AABB bounds) {
        return BlockPos.containing(bounds.minX, bounds.minY, bounds.minZ);
    }

    static BlockPos max(AABB bounds) {
        return BlockPos.containing(bounds.maxX - 1, bounds.maxY - 1, bounds.maxZ - 1);
    }

    static CompoundTag readNbt(Path path) throws IOException {
        return NbtIo.readCompressed(path, NbtAccounter.create(16L * 1024 * 1024));
    }

    static void writeNbt(Path path, CompoundTag tag) throws IOException {
        Path temp = path.resolveSibling(path.getFileName() + ".tmp");
        NbtIo.writeCompressed(tag, temp);
        Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    record Placement(Identifier id, Vec3 center, float yaw, ZoneColor zone) {
        Placement move(Vec3 offset) {
            return new Placement(id, center.add(offset), yaw, zone);
        }

        CompoundTag encode() {
            CompoundTag tag = new CompoundTag();
            tag.putString("id", id.toString());
            tag.putDouble("x", center.x);
            tag.putDouble("y", center.y);
            tag.putDouble("z", center.z);
            tag.putFloat("yaw", yaw);
            tag.putString("zone", zone.name());
            return tag;
        }

        static Placement decode(CompoundTag tag) {
            Vec3 center = new Vec3(tag.getDouble("x").orElseThrow(), tag.getDouble("y").orElseThrow(),
                tag.getDouble("z").orElseThrow());
            float yaw = tag.getFloat("yaw").orElseThrow();
            if (!Double.isFinite(center.x) || !Double.isFinite(center.y) || !Double.isFinite(center.z)
                || !Float.isFinite(yaw)) {
                throw new IllegalArgumentException("Некорректные координаты расставленного моба.");
            }
            return new Placement(Identifier.parse(tag.getString("id").orElseThrow()), center, yaw,
                ZoneColor.valueOf(tag.getString("zone").orElseThrow()));
        }
    }

    record Snapshot(LayoutLevel layout, List<BlockState> palette, int[] blocks, List<Placement> placements) {}
}
