package ru.layoutcamera;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import ru.layoutcamera.LocalLevels.LayoutLevel;

public final class StageCopies {
    private static final String TEMPORARY_MOB = "layoutcamera_temporary_stage_mob";
    private static final int CLEANUP_TICKS = 10 * 20;
    private static final int COPY_DISTANCE = 1000;
    private static final int BLOCK_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_SKIP_ALL_SIDEEFFECTS;
    private static final Map<Owner, Copy> active = new ConcurrentHashMap<>();
    private static final Map<ServerLevel, Map<Path, Location>> pending = new HashMap<>();

    private StageCopies() {}

    static int create(CommandSourceStack source, String name) {
        ServerPlayer player = source.getPlayer();
        ServerLevel level = player.level();
        Owner owner = new Owner(level.getServer(), player.getUUID());
        try {
            Path folder = StageFiles.directory(name);
            Path file = folder.resolve("stage.nbt");
            boolean firstUse = Files.notExists(file);
            LayoutLevel draft = level.getServer().isSingleplayerOwner(player.nameAndId())
                ? ZoneSelection.draftFor(level, name) : null;
            boolean saveSnapshot = firstUse;
            StageFiles.Snapshot snapshot;
            if (firstUse) {
                if (draft == null) {
                    source.sendFailure(Component.literal("Сохранение уровня " + name + " не найдено."));
                    return 0;
                }
                var placements = PlanningMobs.captureStagePlacements(ZoneSelection.sourcePlacementFile(level), draft);
                snapshot = StageFiles.capture(level, draft, placements);
            } else {
                StageFiles.Snapshot saved = StageFiles.load(folder);
                snapshot = saved;
                if (draft != null && saved.layout().id().equals(draft.id())) {
                    var placements = PlanningMobs.captureStagePlacements(ZoneSelection.sourcePlacementFile(level), draft);
                    snapshot = StageFiles.updateFromDraft(saved, draft, placements);
                }
                saveSnapshot = saved != snapshot;
            }
            for (ServerLevel dimension : level.getServer().getAllLevels()) {
                recoverReady(dimension);
            }
            AABB bounds = findSpace(level, snapshot.layout().bounds(), player.blockPosition(), owner);
            if (bounds == null) {
                throw new IllegalArgumentException("Наверху нет свободного места с необходимым расстоянием между копиями.");
            }
            Copy previous = active.get(owner);
            if (previous != null) {
                remove(previous, player);
            }
            Vec3 origin = new Vec3(bounds.minX, bounds.minY, bounds.minZ);
            LayoutLevel layout = StageFiles.translate(snapshot.layout(), origin, UUID.randomUUID());
            Location location = new Location(layout.bounds(), player.getUUID(), player.level().dimension(),
                player.position());
            Path journal = journal(level, owner.player());
            StageFiles.writeNbt(journal, location.encode());
            try {
                paste(level, bounds, snapshot);
                BlockPos landing = findLanding(level, bounds);
                if (landing == null) {
                    throw new IllegalArgumentException("В копии уровня нет площадки с двумя свободными блоками для входа.");
                }
                if (saveSnapshot) {
                    StageFiles.save(folder, snapshot);
                }
                teleport(player, level, Vec3.atBottomCenterOf(landing));
                PlanningMobs.addStage(layout.id(), snapshot.placements(), origin);
                active.put(owner, new Copy(level, name, snapshot.layout().id(), layout, location, journal));
            } catch (IOException | RuntimeException exception) {
                PlanningMobs.removeStage(layout.id());
                try {
                    erase(level, location, journal);
                } catch (IOException | RuntimeException cleanupFailure) {
                    exception.addSuppressed(cleanupFailure);
                    pending.get(level).put(journal, location);
                }
                throw exception;
            }
            StagePlanning.send(player, layout);
            source.sendSuccess(() -> Component.literal("Уровень " + name + " создан наверху мира."), false);
            return 1;
        } catch (IOException | RuntimeException exception) {
            LogUtils.getLogger().warn("Could not create stage {}", name, exception);
            source.sendFailure(Component.literal("Уровень " + name + " · " + exception.getMessage()));
            return 0;
        }
    }

    static int remove(CommandSourceStack source, String name) {
        return removeNamed(source, name, false);
    }

    static int deleteDraft(CommandSourceStack source, String name) {
        return removeNamed(source, name, true);
    }

    private static int removeNamed(CommandSourceStack source, String name, boolean deleteDraft) {
        ServerPlayer player = source.getPlayer();
        ServerLevel commandLevel = player.level();
        try {
            Path folder = StageFiles.directory(name);
            List<Copy> copies = active.values().stream()
                .filter(copy -> copy.level.getServer() == source.getServer() && copy.name.equals(name)).toList();
            for (Copy copy : copies) {
                ServerPlayer copyOwner = source.getServer().getPlayerList().getPlayer(copy.location.owner());
                if (copyOwner != null) {
                    remove(copy, copyOwner);
                } else {
                    copy.retired = true;
                    copy.remainingTicks = 0;
                }
            }
            boolean draftRemoved = false;
            if (deleteDraft) {
                UUID sourceId = copies.isEmpty() ? readSourceId(folder) : copies.getFirst().sourceId;
                draftRemoved = ZoneSelection.deleteStageDraft(commandLevel, player.getUUID(), name, sourceId);
            }
            if (copies.isEmpty() && !draftRemoved) {
                source.sendFailure(Component.literal("Нет активных копий"
                    + (deleteDraft ? " или исходной разметки уровня " : " уровня ") + name + "."));
                return 0;
            }
            String removed = draftRemoved ? "Исходная разметка уровня и его активные копии убраны."
                : "Активные копии уровня убраны.";
            source.sendSuccess(() -> Component.literal(name + " · " + removed + " Папка Stage сохранена."), false);
            return 1;
        } catch (IOException | RuntimeException exception) {
            LogUtils.getLogger().warn("Could not delete stage {}", name, exception);
            source.sendFailure(Component.literal("Не удалось удалить уровень " + name + " · " + exception.getMessage()));
            return 0;
        }
    }

    static int removeCopy(CommandSourceStack source, String name) {
        ServerPlayer player = source.getPlayer();
        Copy copy = active.get(new Owner(source.getServer(), player.getUUID()));
        if (copy == null || !copy.name.equals(name)) {
            source.sendFailure(Component.literal("Активная копия уровня " + name + " не найдена."));
            return 0;
        }
        try {
            remove(copy, player);
            source.sendSuccess(() -> Component.literal("Копия уровня " + name + " убрана. Сохранение осталось в Stage."), false);
            return 1;
        } catch (IOException | RuntimeException exception) {
            LogUtils.getLogger().warn("Could not remove the temporary copy of stage {}", name, exception);
            source.sendFailure(Component.literal("Не удалось убрать копию уровня " + name + " · " + exception.getMessage()));
            return 0;
        }
    }

    private static UUID readSourceId(Path folder) {
        try {
            return StageFiles.sourceId(folder);
        } catch (IOException exception) {
            LogUtils.getLogger().warn("Could not identify the original layout for {}", folder, exception);
            return null;
        }
    }

    public static void tick(ServerLevel level) {
        if (!level.getServer().isSingleplayer()) {
            return;
        }
        if (level.getGameTime() % 20 == 0 && !level.getServer().getPlayerList().getPlayers().isEmpty()) {
            try {
                recoverReady(level);
            } catch (IOException | RuntimeException exception) {
                LogUtils.getLogger().error("Could not recover temporary Layout Camera stages", exception);
            }
        }
        active.values().stream().filter(copy -> copy.level == level).forEach(StageCopies::tickCopy);
    }

    private static void tickCopy(Copy copy) {
        ServerLevel level = copy.level;
        ServerPlayer owner = level.getServer().getPlayerList().getPlayer(copy.location.owner());
        if (owner == null) {
            return;
        }
        if (!copy.retired && owner.level() == level && LocalLevels.isNear(copy.layout.bounds(), owner.position(), 20)) {
            copy.remainingTicks = -1;
            return;
        }
        if (copy.remainingTicks < 0) {
            copy.remainingTicks = CLEANUP_TICKS;
            owner.sendSystemMessage(Component.literal("Ты дальше 20 блоков от уровня " + copy.name
                + ". Вернись за 10 секунд, иначе временная копия исчезнет."));
            return;
        }
        if (copy.remainingTicks > 0 && --copy.remainingTicks > 0) {
            return;
        }
        try {
            remove(copy, owner);
            owner.sendSystemMessage(Component.literal("Копия уровня " + copy.name + " убрана. Ты вернулся в исходную точку."));
        } catch (IOException | RuntimeException exception) {
            copy.remainingTicks = CLEANUP_TICKS;
            LogUtils.getLogger().error("Could not remove temporary stage {}", copy.name, exception);
        }
    }

    public static void close(ServerLevel level) {
        for (Copy copy : active.values()) {
            if (copy.level == level) {
                active.remove(new Owner(level.getServer(), copy.location.owner()), copy);
                PlanningMobs.removeStage(copy.layout.id());
            }
        }
        pending.remove(level);
    }

    static LayoutLevel layout(MinecraftServer server, ResourceKey<Level> dimension, UUID owner) {
        Copy copy = active.get(new Owner(server, owner));
        return copy == null || !copy.level.dimension().equals(dimension) ? null : copy.layout;
    }

    static List<LayoutLevel> layouts(ServerLevel level) {
        return active.values().stream().filter(copy -> copy.level == level).map(copy -> copy.layout).toList();
    }

    static boolean isTemporary(UUID scope) {
        return scope != null && active.values().stream().anyMatch(copy -> scope.equals(copy.layout.id()));
    }

    static void markMob(Entity entity, UUID scope) {
        if (isTemporary(scope)) {
            entity.addTag(TEMPORARY_MOB);
        }
    }

    public static boolean isTemporaryMob(Entity entity) {
        return entity.entityTags().contains(TEMPORARY_MOB);
    }

    private static AABB findSpace(ServerLevel level, AABB original, BlockPos near, Owner owner) {
        int width = (int) original.getXsize();
        int height = (int) original.getYsize();
        int depth = (int) original.getZsize();
        if (height + 2 > level.getHeight()) {
            throw new IllegalArgumentException("Уровень не помещается по высоте с двумя блоками свободного пространства.");
        }
        int y = level.getMaxY() - height - 1;
        AABB base = original.move(near.getX() - width / 2, y, near.getZ() - depth / 2);
        Copy ownCopy = active.get(owner);
        AABB ownBounds = ownCopy != null && ownCopy.level == level ? ownCopy.layout.bounds() : null;
        List<AABB> occupied = new ArrayList<>(active.values().stream().filter(copy -> copy.level == level && copy != ownCopy)
            .map(copy -> copy.layout.bounds()).toList());
        pending.get(level).values().forEach(location -> occupied.add(location.bounds()));
        for (AABB candidate : spaceCandidates(base, occupied)) {
            if ((ownBounds == null || !ownBounds.intersects(candidate))
                && level.getWorldBorder().isWithinBounds(StageFiles.min(candidate))
                && level.getWorldBorder().isWithinBounds(StageFiles.max(candidate))
                && ZoneSelection.canPlaceTemporary(level, candidate)
                && isEmpty(level, candidate.expandTowards(0, 2, 0))) {
                return candidate;
            }
        }
        return null;
    }

    static List<AABB> spaceCandidates(AABB base, List<AABB> occupied) {
        Set<AABB> candidates = new LinkedHashSet<>();
        int step = (int) Math.max(base.getXsize(), base.getZsize()) + 8;
        addGrid(candidates, base, step, 2);
        if (!occupied.isEmpty()) {
            for (AABB other : occupied) {
                candidates.add(base.move(other.minX - COPY_DISTANCE - base.maxX, 0, 0));
                candidates.add(base.move(other.maxX + COPY_DISTANCE - base.minX, 0, 0));
                candidates.add(base.move(0, 0, other.minZ - COPY_DISTANCE - base.maxZ));
                candidates.add(base.move(0, 0, other.maxZ + COPY_DISTANCE - base.minZ));
            }
            int rings = 2 + (int) Math.ceil(Math.sqrt(occupied.size()));
            addGrid(candidates, base, COPY_DISTANCE + 128, rings);
        }
        Vec3 center = base.getCenter();
        return candidates.stream()
            .filter(candidate -> occupied.stream().allMatch(other -> hasCopyDistance(candidate, other)))
            .sorted(Comparator.comparingDouble(candidate -> center.distanceToSqr(candidate.getCenter()))).toList();
    }

    private static void addGrid(Set<AABB> candidates, AABB base, int spacing, int radius) {
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                candidates.add(base.move(x * spacing, 0, z * spacing));
            }
        }
    }

    static boolean hasCopyDistance(AABB first, AABB second) {
        double x = Math.max(0, Math.max(first.minX - second.maxX, second.minX - first.maxX));
        double z = Math.max(0, Math.max(first.minZ - second.maxZ, second.minZ - first.maxZ));
        return x * x + z * z >= (double) COPY_DISTANCE * COPY_DISTANCE;
    }

    private static boolean isEmpty(ServerLevel level, AABB bounds) {
        for (BlockPos position : StageFiles.positions(bounds)) {
            if (!level.getBlockState(position).isAir()) {
                return false;
            }
        }
        return true;
    }

    private static void paste(ServerLevel level, AABB bounds, StageFiles.Snapshot snapshot) {
        int index = 0;
        for (BlockPos position : StageFiles.positions(bounds)) {
            var state = snapshot.palette().get(snapshot.blocks()[index++]);
            if (!state.isAir() && !level.setBlock(position, state, BLOCK_FLAGS)) {
                throw new IllegalStateException("Не удалось разместить блок в " + position.toShortString());
            }
        }
    }

    private static BlockPos findLanding(ServerLevel level, AABB bounds) {
        Vec3 center = bounds.getCenter();
        BlockPos best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (BlockPos floor : StageFiles.positions(bounds)) {
            BlockPos feet = floor.above();
            var state = level.getBlockState(floor);
            if (!state.isAir() && state.getFluidState().isEmpty() && !state.is(Blocks.MAGMA_BLOCK)
                && state.isFaceSturdy(level, floor, Direction.UP) && level.getBlockState(feet).isAir()
                && level.getBlockState(feet.above()).isAir()) {
                double score = (feet.getY() == bounds.maxY ? 100_000 : 0)
                    + Vec3.atBottomCenterOf(feet).distanceToSqr(center);
                if (score < bestScore) {
                    best = feet;
                    bestScore = score;
                }
            }
        }
        return best;
    }

    private static void teleport(ServerPlayer player, ServerLevel level, Vec3 destination) throws IOException {
        if (!player.teleportTo(level, destination.x, destination.y, destination.z, Set.of(),
            player.getYRot(), player.getXRot(), false)) {
            throw new IOException("Не удалось переместить игрока.");
        }
        player.setDeltaMovement(Vec3.ZERO);
        player.resetFallDistance();
    }

    private static String journalPrefix(ServerLevel level) {
        UUID key = UUID.nameUUIDFromBytes(level.dimension().identifier().toString().getBytes(StandardCharsets.UTF_8));
        return "layoutcamera-stage-" + key;
    }

    private static Path journal(ServerLevel level, UUID player) {
        return level.getServer().getWorldPath(LevelResource.ROOT).resolve(journalPrefix(level) + "-" + player + ".nbt");
    }

    private static void recoverReady(ServerLevel level) throws IOException {
        Map<Path, Location> entries = pending.get(level);
        if (entries == null) {
            entries = readJournals(level);
            pending.put(level, entries);
        }
        var iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            Location location = entry.getValue();
            ServerPlayer owner = level.getServer().getPlayerList().getPlayer(location.owner());
            if (owner != null) {
                returnPlayer(level.getServer(), location, owner);
                erase(level, location, entry.getKey());
                iterator.remove();
            }
        }
    }

    private static Map<Path, Location> readJournals(ServerLevel level) throws IOException {
        Path folder = level.getServer().getWorldPath(LevelResource.ROOT);
        String prefix = journalPrefix(level);
        Map<Path, Location> entries = new HashMap<>();
        try (var paths = Files.newDirectoryStream(folder, prefix + "*.nbt")) {
            for (Path path : paths) {
                String name = path.getFileName().toString();
                if (!name.equals(prefix + ".nbt") && !name.substring(prefix.length())
                    .matches("-[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}\\.nbt")) {
                    continue;
                }
                Location location = Location.decode(StageFiles.readNbt(path), level.dimension());
                if (!name.equals(prefix + ".nbt") && !path.equals(journal(level, location.owner()))) {
                    throw new IOException("Владелец временной копии не совпадает с журналом " + name);
                }
                entries.put(path, location);
            }
        }
        return entries;
    }

    private static void remove(Copy copy, ServerPlayer owner) throws IOException {
        ServerLevel level = copy.level;
        returnPlayer(level.getServer(), copy.location, owner);
        SpawnZones.cancel(level, copy.layout.id());
        erase(level, copy.location, copy.journal);
        active.remove(new Owner(level.getServer(), copy.location.owner()), copy);
        PlanningMobs.removeStage(copy.layout.id());
        StagePlanning.send(owner, null);
    }

    private static void returnPlayer(MinecraftServer server, Location location, ServerPlayer owner) throws IOException {
        if (owner == null) {
            return;
        }
        ServerLevel destination = server.getLevel(location.returnDimension());
        if (destination == null) {
            throw new IOException("Исходное измерение игрока недоступно.");
        }
        teleport(owner, destination, location.returnPosition());
    }

    private static void erase(ServerLevel level, Location location, Path journal) throws IOException {
        AABB bounds = location.bounds();
        if (!level.isInWorldBounds(StageFiles.min(bounds)) || !level.isInWorldBounds(StageFiles.max(bounds))) {
            throw new IOException("Граница временной копии выходит за пределы мира.");
        }
        for (BlockPos position : StageFiles.positions(bounds)) {
            if (!level.getBlockState(position).isAir() && !level.setBlock(position, Blocks.AIR.defaultBlockState(), BLOCK_FLAGS)) {
                throw new IOException("Не удалось удалить временный блок в " + position.toShortString());
            }
        }
        // Keep the journal until the cleared chunks are on disk.
        level.save(null, true, false);
        Files.deleteIfExists(journal);
    }

    private record Owner(MinecraftServer server, UUID player) {}

    private record Location(AABB bounds, UUID owner, ResourceKey<Level> returnDimension, Vec3 returnPosition) {
        CompoundTag encode() {
            CompoundTag tag = new CompoundTag();
            tag.putLong("min", StageFiles.min(bounds).asLong());
            tag.putLong("max", StageFiles.max(bounds).asLong());
            tag.putString("owner", owner.toString());
            tag.putString("return_dimension", returnDimension.identifier().toString());
            tag.putDouble("return_x", returnPosition.x);
            tag.putDouble("return_y", returnPosition.y);
            tag.putDouble("return_z", returnPosition.z);
            return tag;
        }

        static Location decode(CompoundTag tag, ResourceKey<Level> stageDimension) {
            BlockPos min = BlockPos.of(tag.getLong("min").orElseThrow());
            BlockPos max = BlockPos.of(tag.getLong("max").orElseThrow());
            if (min.getX() > max.getX() || min.getY() > max.getY() || min.getZ() > max.getZ()) {
                throw new IllegalArgumentException("Некорректная граница временной копии.");
            }
            AABB bounds = new AABB(min.getX(), min.getY(), min.getZ(), max.getX() + 1, max.getY() + 1, max.getZ() + 1);
            StageFiles.volume(bounds);
            Vec3 returnPosition = new Vec3(tag.getDouble("return_x").orElseThrow(),
                tag.getDouble("return_y").orElseThrow(), tag.getDouble("return_z").orElseThrow());
            if (!Double.isFinite(returnPosition.x) || !Double.isFinite(returnPosition.y) || !Double.isFinite(returnPosition.z)) {
                throw new IllegalArgumentException("Некорректная позиция возврата игрока.");
            }
            ResourceKey<Level> returnDimension = tag.getString("return_dimension")
                .map(name -> ResourceKey.create(Registries.DIMENSION, Identifier.parse(name))).orElse(stageDimension);
            return new Location(bounds, UUID.fromString(tag.getString("owner").orElseThrow()), returnDimension,
                returnPosition);
        }
    }

    private static final class Copy {
        private final ServerLevel level;
        private final String name;
        private final UUID sourceId;
        private final LayoutLevel layout;
        private final Location location;
        private final Path journal;
        private int remainingTicks = -1;
        private boolean retired;

        Copy(ServerLevel level, String name, UUID sourceId, LayoutLevel layout, Location location, Path journal) {
            this.level = level;
            this.name = name;
            this.sourceId = sourceId;
            this.layout = layout;
            this.location = location;
            this.journal = journal;
        }
    }
}
