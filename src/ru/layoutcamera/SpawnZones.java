package ru.layoutcamera;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntitySpawnRequest;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import ru.layoutcamera.LocalLevels.LayoutLevel;
import ru.layoutcamera.PlanningMobs.PlacementPoint;
import ru.layoutcamera.ZoneSelection.ZoneColor;

public final class SpawnZones {
    private static final Path CONFIG = PetConfig.PROJECT_DIRECTORY.resolve("config/layout-camera/spawn.json");
    private static final Map<ServerLevel, Map<UUID, Run>> runs = new WeakHashMap<>();

    private SpawnZones() {}

    static synchronized int start(CommandSourceStack source) {
        ServerLevel level = source.getPlayer().level();
        Vec3 position = source.getPlayer().position();
        LayoutLevel layout = ZoneSelection.layoutAt(level, source.getPlayer().getUUID(), position);
        UUID scope = layout == null ? null : layout.id();
        Map<UUID, Run> dimensionRuns = runs.get(level);
        Run run = dimensionRuns == null ? null : dimensionRuns.get(scope);
        try {
            Map<Identifier, PetConfig> pets = PetConfig.load();
            Map<UUID, Sequence> sequences = layout == null
                ? startLegacySequences(level, position, run, pets)
                : startLocalSequences(source, layout, position, run, pets);
            if (sequences.isEmpty()) {
                return 0;
            }
            if (run == null) {
                run = new Run(source.getPlayer().getUUID());
            } else {
                removeFallenDefenders(run);
            }
            for (var placement : PlanningMobs.defenderPlacements(level, scope)) {
                if (run.defenders.containsKey(placement.center())) {
                    continue;
                }
                Defender defender = spawnDefender(level, layout, pets.get(placement.id()), placement);
                if (defender != null) {
                    run.defenders.put(placement.center(), defender);
                }
            }
            run.sequences.putAll(sequences);
            runs.computeIfAbsent(level, ignored -> new HashMap<>()).put(scope, run);
            long total = sequences.values().stream().mapToLong(sequence -> sequence.config.total()).sum();
            source.sendSuccess(() -> Component.literal("Спавн запущен · " + total + " мобов"), false);
            return 1;
        } catch (IOException | IllegalArgumentException exception) {
            source.sendFailure(Component.literal("Не удалось запустить спавн: " + exception.getMessage()));
            return 0;
        }
    }

    private static Map<UUID, Sequence> startLegacySequences(ServerLevel level, Vec3 position, Run run,
                                                            Map<Identifier, PetConfig> pets) throws IOException {
        if (run != null && run.sequences.containsKey(null)) {
            throw new IllegalArgumentException("Спавн вне уровней уже запущен.");
        }
        List<AABB> nearby = ZoneSelection.zonesOfColor(level, ZoneColor.RED).stream()
            .filter(zone -> LocalLevels.isNear(zone, position)).toList();
        requireNearby(nearby.isEmpty());
        Map<UUID, Sequence> sequences = new HashMap<>();
        sequences.put(null, createSequence(level, nearby, ZoneSelection.zonesOfColor(level, ZoneColor.WHITE),
            Config.load(CONFIG), pets));
        return sequences;
    }

    private static Map<UUID, Sequence> startLocalSequences(CommandSourceStack source, LayoutLevel layout,
                                                            Vec3 position, Run run, Map<Identifier, PetConfig> pets) {
        List<LocalLevels.Zone> nearby = layout.zones().values().stream()
            .filter(zone -> zone.color() == ZoneColor.RED && zone.settings().enabled())
            .filter(zone -> LocalLevels.isNear(zone.bounds(), position)).toList();
        requireNearby(nearby.isEmpty());
        List<AABB> finishes = layout.zones().values().stream()
            .filter(zone -> zone.color() == ZoneColor.WHITE && zone.settings().enabled())
            .map(LocalLevels.Zone::bounds).toList();
        Map<UUID, Sequence> sequences = new LinkedHashMap<>();
        for (LocalLevels.Zone zone : nearby) {
            if (run != null && run.sequences.containsKey(zone.id())) {
                source.sendFailure(Component.literal("В этой зоне спавн уже идёт: " + zone.id()));
                continue;
            }
            try {
                sequences.put(zone.id(), createSequence(source.getPlayer().level(), List.of(zone.bounds()), finishes,
                    zone.settings().spawn(), pets));
            } catch (IllegalArgumentException exception) {
                source.sendFailure(Component.literal("Зона " + zone.id() + " · " + exception.getMessage()));
            }
        }
        return sequences;
    }

    private static void requireNearby(boolean empty) {
        if (empty) {
            throw new IllegalArgumentException("В радиусе 10 блоков нет доступной красной зоны спавна.");
        }
    }

    private static Sequence createSequence(ServerLevel level, List<AABB> zones, List<AABB> finishes, Config config,
                                           Map<Identifier, PetConfig> pets) {
        List<SpawnArea> areas = new ArrayList<>();
        for (AABB zone : zones) {
            BlockPos initialSpawn = findInitialSpawn(zone, finishes, config.finishSearchRadius());
            if (initialSpawn != null) {
                areas.add(new SpawnArea(zone, initialSpawn));
            }
        }
        if (areas.isEmpty()) {
            throw new IllegalArgumentException("Нету зоны спавна с финишем в радиусе "
                + config.finishSearchRadius() + " блоков.");
        }
        Map<String, SpawnMob> resolved = new HashMap<>();
        List<SpawnMob> order = config.order().stream()
            .map(id -> resolved.computeIfAbsent(id, key -> resolve(level, pets, key))).toList();
        return new Sequence(List.copyOf(areas), finishes, order, config);
    }

    public static synchronized void tick(ServerLevel level) {
        Map<UUID, Run> dimensionRuns = runs.get(level);
        if (dimensionRuns == null) {
            return;
        }
        dimensionRuns.entrySet().removeIf(entry -> tickRun(level, entry.getKey(), entry.getValue()));
        if (dimensionRuns.isEmpty()) {
            runs.remove(level);
        }
    }

    private static boolean tickRun(ServerLevel level, UUID scope, Run run) {
        boolean savedDefenderDeaths = removeFallenDefenders(run);
        run.enemies.removeIf(mob -> !mob.isAlive());
        run.sequences.values().removeIf(sequence -> tickSequence(level, scope, run, sequence));
        if (run.sequences.isEmpty() && run.enemies.isEmpty() && savedDefenderDeaths) {
            run.defenders.values().forEach(defender -> defender.mob().discard());
            return true;
        }
        return false;
    }

    private static boolean tickSequence(ServerLevel level, UUID scope, Run run, Sequence sequence) {
        if (sequence.waitTicks-- > 0) {
            return false;
        }
        sequence.waitTicks = sequence.config.intervalTicks() - 1;
        SpawnMob next = sequence.order.get(sequence.config.orderIndex(sequence.spawned));
        Mob mob = trySpawn(level, scope, sequence, next);
        if (mob == null) {
            if (!sequence.waitingForSpace) {
                notifyPlayer(level, run, "Спавн ждёт свободного места в красной зоне.");
                sequence.waitingForSpace = true;
            }
            return false;
        }
        run.enemies.add(mob);
        sequence.waitingForSpace = false;
        if (++sequence.spawned == sequence.config.total()) {
            notifyPlayer(level, run, "Спавн завершён · появилось мобов: " + sequence.spawned);
            return true;
        }
        return false;
    }

    static synchronized Set<PlacementPoint> activeDefenders(MinecraftServer server, ResourceKey<Level> dimension) {
        if (server != null) {
            for (var entry : runs.entrySet()) {
                ServerLevel level = entry.getKey();
                if (level != null && level.getServer() == server && level.dimension().equals(dimension)) {
                    Set<PlacementPoint> points = new HashSet<>();
                    entry.getValue().forEach((scope, run) -> run.defenders.keySet()
                        .forEach(center -> points.add(new PlacementPoint(scope, center))));
                    return points;
                }
            }
        }
        return Set.of();
    }

    public static synchronized void close(ServerLevel level) {
        Map<UUID, Run> dimensionRuns = runs.remove(level);
        if (dimensionRuns != null) {
            dimensionRuns.values().forEach(SpawnZones::removeFallenDefenders);
        }
    }

    static synchronized void cancel(ServerLevel level, UUID scope) {
        Map<UUID, Run> dimensionRuns = runs.get(level);
        if (dimensionRuns == null) {
            return;
        }
        Run run = dimensionRuns.remove(scope);
        if (run != null) {
            run.enemies.forEach(Mob::discard);
            run.defenders.values().forEach(defender -> defender.mob().discard());
        }
        if (dimensionRuns.isEmpty()) {
            runs.remove(level);
        }
    }

    private static boolean removeFallenDefenders(Run run) {
        boolean saved = true;
        var defenders = run.defenders.values().iterator();
        while (defenders.hasNext()) {
            Defender defender = defenders.next();
            if (defender.permanentDeath() && defender.mob().isDeadOrDying()) {
                if (PlanningMobs.removeDefender(defender.placement())) {
                    defenders.remove();
                } else {
                    saved = false;
                }
            }
        }
        return saved;
    }

    private static Defender spawnDefender(ServerLevel level, LayoutLevel layout, PetConfig pet,
                                          PlanningMobs.DefenderPlacement placement) {
        if (!level.hasChunkAt(BlockPos.containing(placement.center()))) {
            return null;
        }
        var preferredZone = placement.zone() != null ? placement.zone()
            : pet == null ? ZoneColor.BLUE : pet.placementZone();
        var currentZone = ZoneSelection.placementColorAt(level, layout, placement.center(), preferredZone, placement.id());
        if (layout != null && currentZone == null) {
            return null;
        }
        EntityType<?> type = pet == null
            ? (placement.id().equals(PetConfig.PLANNING_CHICKEN) ? net.minecraft.world.entity.EntityTypes.CHICKEN : null)
            : BuiltInRegistries.ENTITY_TYPE.getOptional(pet.model()).orElse(null);
        if (type == null || !type.isEnabled(level.enabledFeatures())
            || !(type.create(level, spawnRequest(type, level.getDifficulty())) instanceof Mob mob)) {
            return null;
        }
        if (pet == null) {
            FactionCombat.assign(mob, null);
            mob.setNoAi(true);
        } else {
            Pets.configureSummonedMob(mob, pet);
        }
        FactionCombat.markPlanned(mob);
        LocalLevels.mark(mob, placement.scope());
        AttackZones.markPlacement(mob, currentZone);
        var placedZone = placement.zone() == null && currentZone != null ? currentZone : preferredZone;
        boolean permanentDeath = placedZone == ZoneSelection.ZoneColor.BLUE || placedZone == ZoneSelection.ZoneColor.GREEN;
        mob.setPersistenceRequired();
        mob.snapTo(placement.center(), placement.yaw(), 0);
        mob.yHeadRot = mob.yBodyRot = placement.yaw();
        return level.noCollision(mob) && level.addFreshEntity(mob) ? new Defender(mob, placement, permanentDeath) : null;
    }

    private static SpawnMob resolve(ServerLevel level, Map<Identifier, PetConfig> pets, String id) {
        PetConfig pet = pets.get(PetConfig.petId(id));
        Identifier typeId = pet == null ? Identifier.tryParse(id) : pet.model();
        EntityType<?> type = typeId == null ? null : BuiltInRegistries.ENTITY_TYPE.getOptional(typeId).orElse(null);
        if (type == null || !type.canSummon()) {
            throw new IllegalArgumentException("Неизвестный или недоступный ID моба: " + id);
        }
        if (!type.isEnabled(level.enabledFeatures())) {
            throw new IllegalArgumentException("Моб отключён настройками мира: " + id);
        }
        if (!(type.create(level, spawnRequest(type, level.getDifficulty())) instanceof Mob)) {
            throw new IllegalArgumentException("ID не соответствует типу моба: " + id);
        }
        return new SpawnMob(type, pet);
    }

    private static Mob trySpawn(ServerLevel level, UUID scope, Sequence sequence, SpawnMob next) {
        if (!(next.type().create(level, spawnRequest(next.type(), level.getDifficulty())) instanceof Mob mob)) {
            return null;
        }
        if (next.pet() != null) {
            Pets.configureSummonedMob(mob, next.pet());
        } else {
            mob.setPersistenceRequired();
            FactionCombat.assign(mob, null);
        }
        mob.setNoAi(false);
        LocalLevels.mark(mob, scope);
        StageCopies.markMob(mob, scope);
        var attackZones = next.pet() == null ? null : next.pet().attackZones();
        AttackZones.assign(mob, attackZones == null ? sequence.config.attackZones() : attackZones);

        for (int offset = 0; offset < sequence.zones.size(); offset++) {
            int index = (sequence.nextZone + offset) % sequence.zones.size();
            SpawnArea area = sequence.zones.get(index);
            AABB box = area.bounds();
            for (int attempt = 0; attempt < 9; attempt++) {
                BlockPos pos = attempt == 0 ? area.initialSpawn()
                    : BlockPos.containing(box.minX + level.getRandom().nextInt((int) box.getXsize()), box.maxY,
                        box.minZ + level.getRandom().nextInt((int) box.getZsize()));
                if (!level.isInWorldBounds(pos) || !level.getWorldBorder().isWithinBounds(pos)
                    || !level.hasChunkAt(pos) || level.getBlockState(pos.below()).isAir()) {
                    continue;
                }
                BlockPos finish = nearestFinish(pos, sequence.finishes, sequence.config.finishSearchRadius());
                if (finish == null) {
                    continue;
                }
                float yaw = level.getRandom().nextFloat() * 360;
                mob.snapTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, yaw, 0);
                mob.yHeadRot = mob.yBodyRot = yaw;
                if (level.noCollision(mob) && level.addFreshEntity(mob)) {
                    FinishRouteGoal.assign(mob, finish);
                    sequence.nextZone = (index + 1) % sequence.zones.size();
                    return mob;
                }
            }
        }
        return null;
    }

    static EntitySpawnRequest spawnRequest(EntityType<?> type, Difficulty difficulty) {
        return new EntitySpawnRequest(EntitySpawnReason.COMMAND,
            difficulty == Difficulty.PEACEFUL && !type.isAllowedInPeaceful());
    }

    private static BlockPos findInitialSpawn(AABB zone, List<AABB> finishes, int radius) {
        BlockPos center = pointOnTop(zone, zone.getCenter());
        if (nearestFinish(center, finishes, radius) != null) {
            return center;
        }
        for (AABB finish : finishes) {
            BlockPos edge = pointOnTop(zone, finish.getCenter());
            if (nearestFinish(edge, List.of(finish), radius) != null) {
                return edge;
            }
        }
        return null;
    }

    static BlockPos nearestFinish(BlockPos spawn, List<AABB> finishes, int radius) {
        Vec3 from = Vec3.atBottomCenterOf(spawn);
        double nearestDistance = (double) radius * radius;
        BlockPos nearest = null;
        for (AABB box : finishes) {
            BlockPos candidate = pointOnTop(box, from);
            double distance = from.distanceToSqr(Vec3.atBottomCenterOf(candidate));
            if (distance <= nearestDistance && (nearest == null || distance < nearestDistance)) {
                nearest = candidate;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    private static BlockPos pointOnTop(AABB box, Vec3 near) {
        return BlockPos.containing(Math.clamp(near.x, box.minX, box.maxX - 1), box.maxY,
            Math.clamp(near.z, box.minZ, box.maxZ - 1));
    }

    private static void notifyPlayer(ServerLevel level, Run run, String message) {
        var player = level.getServer().getPlayerList().getPlayer(run.player);
        if (player != null) {
            player.sendSystemMessage(Component.literal(message));
        }
    }

    record Config(List<String> order, int intervalTicks, int total, int finishSearchRadius,
                  Set<ZoneSelection.ZoneColor> attackZones) {
        static Config load(Path file) throws IOException {
            try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                return fromJson(JsonParser.parseReader(reader).getAsJsonObject(), null);
            } catch (RuntimeException exception) {
                throw new IOException("Проверь " + file.getFileName() + ": " + exception.getMessage(), exception);
            }
        }

        static Config fromJson(JsonObject json, Config defaults) {
            List<String> order;
            if (defaults != null && !json.has("order")) {
                order = defaults.order();
            } else {
                var entries = json.get("order");
                if (entries == null || !entries.isJsonArray()) {
                    throw new IllegalArgumentException("В order нужен список ID мобов.");
                }
                List<String> parsed = new ArrayList<>();
                for (var entry : entries.getAsJsonArray()) {
                    if (!entry.isJsonPrimitive() || !entry.getAsJsonPrimitive().isString()
                        || entry.getAsString().isBlank()) {
                        throw new IllegalArgumentException("order должен содержать ID мобов.");
                    }
                    parsed.add(entry.getAsString().strip());
                }
                if (parsed.isEmpty()) {
                    throw new IllegalArgumentException("В order нужен хотя бы один ID моба.");
                }
                order = List.copyOf(parsed);
            }
            int interval = defaults != null && !json.has("interval_ticks") ? defaults.intervalTicks()
                : positiveInteger(json, "interval_ticks");
            int total = defaults != null && !json.has("total_enemies") ? defaults.total()
                : positiveInteger(json, "total_enemies");
            int radius = json.has("finish_search_radius") ? positiveInteger(json, "finish_search_radius")
                : defaults == null ? 50 : defaults.finishSearchRadius();
            var attackZones = defaults != null && !json.has("attack_zones") ? defaults.attackZones()
                : AttackZones.read(json.get("attack_zones"));
            return new Config(order, interval, total, radius, attackZones);
        }

        int orderIndex(int alreadySpawned) {
            return alreadySpawned % order.size();
        }

        private static int positiveInteger(JsonObject json, String key) {
            var value = json.get(key);
            if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
                try {
                    int number = value.getAsBigDecimal().intValueExact();
                    if (number > 0) {
                        return number;
                    }
                } catch (ArithmeticException exception) {
                    // Values outside positive integer range are invalid.
                }
            }
            throw new IllegalArgumentException(key + " должен быть положительным целым числом.");
        }
    }

    private record SpawnMob(EntityType<?> type, PetConfig pet) {}
    private record SpawnArea(AABB bounds, BlockPos initialSpawn) {}
    private record Defender(Mob mob, PlanningMobs.DefenderPlacement placement, boolean permanentDeath) {}

    private static final class Run {
        private final UUID player;
        private final List<Mob> enemies = new ArrayList<>();
        private final Map<Vec3, Defender> defenders = new HashMap<>();
        private final Map<UUID, Sequence> sequences = new LinkedHashMap<>();

        Run(UUID player) {
            this.player = player;
        }
    }

    private static final class Sequence {
        private final List<SpawnArea> zones;
        private final List<AABB> finishes;
        private final List<SpawnMob> order;
        private final Config config;
        private int spawned;
        private int waitTicks;
        private int nextZone;
        private boolean waitingForSpace;

        Sequence(List<SpawnArea> zones, List<AABB> finishes, List<SpawnMob> order, Config config) {
            this.zones = zones;
            this.finishes = finishes;
            this.order = order;
            this.config = config;
        }
    }
}
