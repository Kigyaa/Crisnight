package ru.layoutcamera;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
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
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity.RemovalReason;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntitySpawnRequest;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import ru.layoutcamera.LocalLevels.LayoutLevel;
import ru.layoutcamera.PlanningMobs.ActiveDefender;
import ru.layoutcamera.PlanningMobs.PlacementPoint;
import ru.layoutcamera.ZoneSelection.ZoneColor;

public final class SpawnZones {
    private static final Path CONFIG = PetConfig.PROJECT_DIRECTORY.resolve("config/layout-camera/spawn.json");
    private static final int PREPARATION_TICKS = 10 * 20;
    private static final Map<ServerLevel, Map<UUID, Run>> runs = new WeakHashMap<>();

    private SpawnZones() {}

    static synchronized int start(CommandSourceStack source) {
        ServerLevel level = source.getPlayer().level();
        Vec3 position = source.getPlayer().position();
        LayoutLevel layout = ZoneSelection.layoutAt(level, source.getPlayer().getUUID(), position);
        UUID scope = layout == null ? null : layout.id();
        if (StageCopies.hasCampaignResult(source.getPlayer(), scope)) {
            source.sendFailure(Component.literal("Испытание завершено. Выйди или перезапусти уровень."));
            return 0;
        }
        Map<UUID, Run> dimensionRuns = runs.get(level);
        Run run = dimensionRuns == null ? null : dimensionRuns.get(scope);
        if (run != null && run.campaign != null) {
            source.sendFailure(Component.literal("Волна уровня из карты уже запущена."));
            return 0;
        }
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
            spawnDefenders(level, layout, scope, run, pets);
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

    static CampaignWave prepareCampaign(ServerLevel level, LayoutLevel layout) throws IOException {
        Map<Identifier, PetConfig> pets = PetConfig.load();
        Map<UUID, Sequence> sequences = new LinkedHashMap<>();
        List<AABB> finishes = enabledFinishes(layout);
        for (var zone : layout.zones().values()) {
            if (zone.color() == ZoneColor.RED && zone.settings().enabled()) {
                try {
                    sequences.put(zone.id(), createSequence(level, List.of(zone.bounds()), finishes,
                        zone.settings().spawn(), pets, zone.route(), layout.bounds()));
                } catch (IllegalArgumentException exception) {
                    throw new IllegalArgumentException("Зона " + zone.id() + " · " + exception.getMessage(), exception);
                }
            }
        }
        if (sequences.isEmpty()) {
            throw new IllegalArgumentException("В уровне нет включённых красных зон спавна.");
        }
        return new CampaignWave(layout, sequences, pets);
    }

    static synchronized void startCampaign(ServerLevel level, ServerPlayer player, Campaign.LevelEntry entry,
                                           CampaignWave wave) {
        Run run = new Run(player.getUUID(), entry);
        run.campaignWave = wave;
        run.preparationTicks = PREPARATION_TICKS;
        run.sequences.putAll(wave.sequences());
        UUID scope = wave.layout().id();
        runs.computeIfAbsent(level, ignored -> new HashMap<>()).put(scope, run);
        publishCampaign(level, scope, run);
    }

    static void showStageRoutes(ServerLevel level, LayoutLevel layout, CampaignWave wave) {
        List<AABB> finishes = enabledFinishes(layout);
        if (finishes.isEmpty()) {
            return;
        }
        Map<Identifier, PetConfig> pets;
        try {
            pets = wave == null ? PetConfig.load() : wave.pets();
        } catch (IOException exception) {
            LogUtils.getLogger().warn("Could not load mob settings for stage route previews", exception);
            return;
        }
        Map<UUID, List<Vec3>> previews = new LinkedHashMap<>();
        int remaining = RouteParticles.MAX_STAGE_PARTICLES;
        for (var zone : layout.zones().values()) {
            if (zone.color() != ZoneColor.RED || !zone.settings().enabled()) {
                continue;
            }
            try {
                Sequence sequence = wave == null ? createSequence(level, List.of(zone.bounds()), finishes,
                    zone.settings().spawn(), pets, zone.route(), layout.bounds()) : wave.sequences().get(zone.id());
                List<Vec3> path = previewPath(level, sequence);
                if (path.size() > remaining) {
                    break;
                }
                if (!path.isEmpty()) {
                    previews.put(zone.id(), path);
                    remaining -= path.size();
                }
            } catch (IllegalArgumentException ignored) {
                // A manual copy can still open when a zone is not ready to spawn.
            } catch (RuntimeException exception) {
                LogUtils.getLogger().warn("Could not preview the route for zone {}", zone.id(), exception);
            }
        }
        RouteParticles.show(layout.id(), previews);
    }

    private static List<Vec3> previewPath(ServerLevel level, Sequence sequence) {
        SpawnArea area = sequence.zones.getFirst();
        SpawnPoint point = spawnPoint(level, sequence, area, area.initialSpawn());
        if (point == null) {
            return List.of();
        }
        var route = movementRoute(level, sequence, area.initialSpawn(), point);
        if (!route.isEmpty()) {
            return RouteParticles.sample(route.stream().map(WaveRoute::center).toList());
        }
        var type = sequence.order.getFirst().type();
        if (type.create(level, spawnRequest(type, level.getDifficulty())) instanceof Mob mob) {
            mob.snapTo(point.position(), 0, 0);
            mob.setOnGround(true);
            return RouteParticles.sample(renderPath(mob, point.finish()));
        }
        return List.of();
    }

    private static void spawnDefenders(ServerLevel level, LayoutLevel layout, UUID scope, Run run,
                                       Map<Identifier, PetConfig> pets) {
        spawnDefenders(level, layout, run, pets, PlanningMobs.defenderPlacements(level, scope));
    }

    private static void spawnDefenders(ServerLevel level, LayoutLevel layout, Run run, Map<Identifier, PetConfig> pets,
                                       List<PlanningMobs.DefenderPlacement> placements) {
        for (var placement : placements) {
            if (run.defenders.containsKey(placement.center())) {
                continue;
            }
            Defender defender = spawnDefender(level, layout, pets.get(placement.id()), placement);
            if (defender != null) {
                run.defenders.put(placement.center(), defender);
            }
        }
    }

    private static void syncCampaignDefenders(ServerLevel level, UUID scope, Run run) {
        CampaignWave wave = run.campaignWave;
        var placements = PlanningMobs.defenderPlacements(level, scope);
        var current = new HashSet<>(placements);
        run.defenders.values().removeIf(defender -> {
            if (current.contains(defender.placement())) {
                return false;
            }
            defender.mob().discard();
            return true;
        });
        spawnDefenders(level, wave.layout(), run, wave.pets(), placements);
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
            Config.load(CONFIG), pets, List.of(), null));
        return sequences;
    }

    private static Map<UUID, Sequence> startLocalSequences(CommandSourceStack source, LayoutLevel layout,
                                                            Vec3 position, Run run, Map<Identifier, PetConfig> pets) {
        List<LocalLevels.Zone> nearby = layout.zones().values().stream()
            .filter(zone -> zone.color() == ZoneColor.RED && zone.settings().enabled())
            .filter(zone -> LocalLevels.isNear(zone.bounds(), position)).toList();
        requireNearby(nearby.isEmpty());
        List<AABB> finishes = enabledFinishes(layout);
        Map<UUID, Sequence> sequences = new LinkedHashMap<>();
        for (LocalLevels.Zone zone : nearby) {
            if (run != null && run.sequences.containsKey(zone.id())) {
                source.sendFailure(Component.literal("В этой зоне спавн уже идёт: " + zone.id()));
                continue;
            }
            try {
                sequences.put(zone.id(), createSequence(source.getPlayer().level(), List.of(zone.bounds()), finishes,
                    zone.settings().spawn(), pets, zone.route(), layout.bounds()));
            } catch (IllegalArgumentException exception) {
                source.sendFailure(Component.literal("Зона " + zone.id() + " · " + exception.getMessage()));
            }
        }
        return sequences;
    }

    private static List<AABB> enabledFinishes(LayoutLevel layout) {
        return layout.zones().values().stream()
            .filter(zone -> zone.color() == ZoneColor.WHITE && zone.settings().enabled())
            .map(LocalLevels.Zone::bounds).toList();
    }

    private static void requireNearby(boolean empty) {
        if (empty) {
            throw new IllegalArgumentException("В радиусе 10 блоков нет доступной красной зоны спавна.");
        }
    }

    private static Sequence createSequence(ServerLevel level, List<AABB> zones, List<AABB> finishes, Config config,
                                           Map<Identifier, PetConfig> pets, List<BlockPos> route, AABB bounds) {
        List<SpawnArea> areas = new ArrayList<>();
        if (!route.isEmpty() && finishes.stream().noneMatch(finish -> WaveRoute.onZone(finish, route.getLast()))) {
            throw new IllegalArgumentException("Ручной маршрут должен оканчиваться в действующей белой зоне.");
        }
        for (AABB zone : zones) {
            BlockPos initialSpawn = route.isEmpty() ? findInitialSpawn(zone, finishes, config.finishSearchRadius())
                : route.getFirst();
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
        return new Sequence(List.copyOf(areas), finishes, order, config, route, bounds);
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

    static synchronized void reachFinish(Mob mob) {
        if (mob.isAlive() && mob.level() instanceof ServerLevel level) {
            Map<UUID, Run> dimensionRuns = runs.get(level);
            if (dimensionRuns != null) {
                for (Run run : dimensionRuns.values()) {
                    if (run.campaign != null && !run.campaignEnded && run.enemies.remove(mob)) {
                        run.escaped++;
                        break;
                    }
                }
            }
        }
        mob.discard();
    }

    private static boolean tickRun(ServerLevel level, UUID scope, Run run) {
        boolean savedDefenderDeaths = removeFallenDefenders(run);
        if (run.campaign != null && !run.campaignEnded) {
            tickCampaign(level, scope, run);
        } else if (run.campaign == null) {
            run.enemies.removeIf(mob -> !mob.isAlive());
            tickSequences(level, scope, run);
        }
        if (run.sequences.isEmpty() && run.enemies.isEmpty() && savedDefenderDeaths) {
            run.defenders.values().forEach(defender -> defender.mob().discard());
            return true;
        }
        return false;
    }

    private static void tickCampaign(ServerLevel level, UUID scope, Run run) {
        var player = level.getServer().getPlayerList().getPlayer(run.player);
        if (player == null || !player.isAlive() || player.level() != level) {
            stopCampaign(level, scope, run);
            notifyPlayer(level, run, "Испытание прервано · игрок покинул испытание.");
            return;
        }
        if (run.preparationTicks > 0 && --run.preparationTicks > 0) {
            publishCampaign(level, scope, run);
            return;
        }
        if (run.escaped >= Campaign.ESCAPE_LIMIT) {
            stopCampaign(level, scope, run);
            StageCopies.recordResult(player, new Campaign.Result(scope, run.campaign, false, false));
            return;
        }
        if (run.enemies.stream().anyMatch(mob -> mob.isRemoved()
            && mob.getRemovalReason() != RemovalReason.KILLED && !mob.isDeadOrDying())) {
            stopCampaign(level, scope, run);
            notifyPlayer(level, run, "Испытание прервано · моб исчез с поля боя.");
            return;
        }
        run.enemies.removeIf(mob -> !mob.isAlive());
        if (run.elapsedTicks % 10 == 0 && remaining(run) > 0) {
            try {
                syncCampaignDefenders(level, scope, run);
            } catch (RuntimeException exception) {
                LogUtils.getLogger().warn("Could not sync campaign defenders in {}", run.campaign.stage(), exception);
                stopCampaign(level, scope, run);
                notifyPlayer(level, run, "Испытание прервано · не удалось призвать защитников.");
                return;
            }
        }
        tickSequences(level, scope, run);
        if (remaining(run) == 0) {
            stopCampaign(level, scope, run);
            boolean saved = Campaign.saveVictory(player, run.campaign);
            StageCopies.recordResult(player, new Campaign.Result(scope, run.campaign, true, saved));
        } else {
            run.elapsedTicks++;
            publishCampaign(level, scope, run);
        }
    }

    private static void publishCampaign(ServerLevel level, UUID scope, Run run) {
        Campaign.publish(level, run.player, scope, run.campaign, run.elapsedTicks / 20, remaining(run), run.escaped,
            Math.ceilDiv(run.preparationTicks, 20));
    }

    private static long remaining(Run run) {
        return run.enemies.size() + run.sequences.values().stream()
            .mapToLong(sequence -> sequence.config.total() - sequence.spawned).sum();
    }

    private static void stopCampaign(ServerLevel level, UUID scope, Run run) {
        run.campaignEnded = true;
        run.campaignWave = null;
        run.enemies.forEach(Mob::discard);
        run.enemies.clear();
        run.sequences.clear();
        Campaign.clear(level, scope);
    }

    private static void tickSequences(ServerLevel level, UUID scope, Run run) {
        run.sequences.entrySet().removeIf(entry -> tickSequence(level, scope, run, entry.getKey(), entry.getValue()));
    }

    private static boolean tickSequence(ServerLevel level, UUID scope, Run run, UUID zone, Sequence sequence) {
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
        if (sequence.spawned == 0 && RouteParticles.hide(scope, zone)) {
            var player = level.getServer().getPlayerList().getPlayer(run.player);
            if (player != null && !level.getServer().isSingleplayerOwner(player.nameAndId())) {
                var layout = StageCopies.layout(level.getServer(), level.dimension(), run.player);
                if (layout != null && layout.id().equals(scope)) {
                    StagePlanning.send(player, layout);
                }
            }
        }
        if (++sequence.spawned == sequence.config.total()) {
            notifyPlayer(level, run, "Спавн завершён · появилось мобов: " + sequence.spawned);
            return true;
        }
        return false;
    }

    static synchronized Map<PlacementPoint, ActiveDefender> activeDefenders(MinecraftServer server,
                                                                            ResourceKey<Level> dimension) {
        if (server != null) {
            for (var entry : runs.entrySet()) {
                ServerLevel level = entry.getKey();
                if (level != null && level.getServer() == server && level.dimension().equals(dimension)) {
                    Map<PlacementPoint, ActiveDefender> points = new HashMap<>();
                    entry.getValue().forEach((scope, run) -> run.defenders.forEach((center, defender) ->
                        points.put(new PlacementPoint(scope, center),
                            new ActiveDefender(defender.mob().getId(), defender.attackGrid()))));
                    return points;
                }
            }
        }
        return Map.of();
    }

    static synchronized void removeActiveDefender(ServerLevel level, PlacementPoint point, int entityId) {
        var dimensionRuns = runs.get(level);
        var run = dimensionRuns == null ? null : dimensionRuns.get(point.scope());
        var defender = run == null ? null : run.defenders.get(point.center());
        if (defender != null && defender.mob().getId() == entityId) {
            run.defenders.remove(point.center());
            defender.mob().discard();
        }
    }

    public static synchronized void close(ServerLevel level) {
        Map<UUID, Run> dimensionRuns = runs.remove(level);
        if (dimensionRuns != null) {
            dimensionRuns.values().forEach(SpawnZones::removeFallenDefenders);
        }
        Campaign.clear(level, null);
    }

    static synchronized void cancel(ServerLevel level, UUID scope) {
        Campaign.clear(level, scope);
        RouteParticles.clear(scope);
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
        return level.noCollision(mob) && level.addFreshEntity(mob)
            ? new Defender(mob, placement, permanentDeath, AttackGrid.restore(mob)) : null;
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
            for (int attempt = 0; attempt < (sequence.route.isEmpty() ? 9 : 1); attempt++) {
                BlockPos pos = attempt == 0 ? area.initialSpawn()
                    : BlockPos.containing(box.minX + level.getRandom().nextInt((int) box.getXsize()), box.maxY,
                        box.minZ + level.getRandom().nextInt((int) box.getZsize()));
                SpawnPoint point = spawnPoint(level, sequence, area, pos);
                if (point == null) {
                    continue;
                }
                Vec3 direction = (sequence.route.isEmpty() ? Vec3.atBottomCenterOf(point.finish())
                    : WaveRoute.center(sequence.route.get(1))).subtract(point.position());
                float yaw = (float) Math.toDegrees(Math.atan2(-direction.x, direction.z));
                mob.snapTo(point.position(), yaw, 0);
                mob.yHeadRot = mob.yBodyRot = yaw;
                if (level.noCollision(mob) && level.addFreshEntity(mob)) {
                    FinishRouteGoal.assign(mob, point.finish(), movementRoute(level, sequence, pos, point));
                    sequence.nextZone = (index + 1) % sequence.zones.size();
                    return mob;
                }
            }
        }
        return null;
    }

    private static SpawnPoint spawnPoint(ServerLevel level, Sequence sequence, SpawnArea area, BlockPos pos) {
        if (!level.isInWorldBounds(pos) || !level.getWorldBorder().isWithinBounds(pos)
            || !level.hasChunkAt(pos) || level.getBlockState(pos.below()).isAir()) {
            return null;
        }
        BlockPos finish = sequence.route.isEmpty() ? nearestFinish(pos, sequence.finishes, sequence.config.finishSearchRadius())
            : sequence.route.getLast();
        if (finish == null) {
            return null;
        }
        boolean centered = WaveRoute.onZone(area.bounds(), pos) && WaveRoute.supported(level, WaveRoute.center(pos));
        if (!centered && !sequence.route.isEmpty()) {
            return null;
        }
        return new SpawnPoint(centered ? WaveRoute.center(pos) : Vec3.atBottomCenterOf(pos), finish, centered);
    }

    private static List<BlockPos> movementRoute(ServerLevel level, Sequence sequence, BlockPos start, SpawnPoint point) {
        if (!sequence.route.isEmpty() || !point.centered()
            || sequence.finishes.stream().noneMatch(zone -> WaveRoute.onZone(zone, point.finish()))) {
            return sequence.route;
        }
        if (sequence.automatic.size() >= 64) {
            sequence.automatic.clear();
        }
        return sequence.automatic.computeIfAbsent(new RouteEndpoints(start, point.finish()),
            endpoints -> WaveRoute.automatic(level, endpoints.start(), endpoints.finish(), sequence.bounds));
    }

    private static List<Vec3> renderPath(Mob mob, BlockPos finish) {
        var navigation = mob.getNavigation();
        var path = navigation.createPath(finish, 0, FinishRouteGoal.pathRange(mob, finish));
        if (path == null) {
            return List.of();
        }
        List<Vec3> points = new ArrayList<>();
        points.add(mob.position());
        for (int i = 0; i < path.getNodeCount(); i++) {
            points.add(path.getEntityPosAtNode(mob, i));
        }
        return points;
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
        return new BlockPos(topCoordinate(near.x, box.minX, box.maxX), (int) box.maxY,
            topCoordinate(near.z, box.minZ, box.maxZ));
    }

    private static int topCoordinate(double near, double min, double max) {
        return max - min >= 2 ? (int) Math.clamp(Math.round(near), min + 1, max - 1) : (int) min;
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
    private record SpawnPoint(Vec3 position, BlockPos finish, boolean centered) {}
    private record RouteEndpoints(BlockPos start, BlockPos finish) {}
    private record Defender(Mob mob, PlanningMobs.DefenderPlacement placement, boolean permanentDeath,
                            AttackGrid attackGrid) {}

    private static final class Run {
        private final UUID player;
        private final Campaign.LevelEntry campaign;
        private CampaignWave campaignWave;
        private int preparationTicks;
        private long elapsedTicks;
        private int escaped;
        private boolean campaignEnded;
        private final List<Mob> enemies = new ArrayList<>();
        private final Map<Vec3, Defender> defenders = new HashMap<>();
        private final Map<UUID, Sequence> sequences = new LinkedHashMap<>();

        Run(UUID player) {
            this(player, null);
        }

        Run(UUID player, Campaign.LevelEntry campaign) {
            this.player = player;
            this.campaign = campaign;
        }
    }

    record CampaignWave(LayoutLevel layout, Map<UUID, Sequence> sequences, Map<Identifier, PetConfig> pets) {}

    private static final class Sequence {
        private final List<SpawnArea> zones;
        private final List<AABB> finishes;
        private final List<SpawnMob> order;
        private final Config config;
        private final List<BlockPos> route;
        private final AABB bounds;
        private final Map<RouteEndpoints, List<BlockPos>> automatic = new HashMap<>();
        private int spawned;
        private int waitTicks;
        private int nextZone;
        private boolean waitingForSpace;

        Sequence(List<SpawnArea> zones, List<AABB> finishes, List<SpawnMob> order, Config config,
                 List<BlockPos> route, AABB bounds) {
            this.zones = zones;
            this.finishes = finishes;
            this.order = order;
            this.config = config;
            this.route = route;
            this.bounds = bounds;
        }
    }
}
