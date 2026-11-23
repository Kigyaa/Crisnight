package ru.layoutcamera;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import ru.layoutcamera.LocalLevels.LayoutLevel;

public final class PlanningMobs {
    private static final String PREVIEW_TAG = "layoutcamera_planning_mob";
    private static final int DELETE_SIZE = 24;
    private static final int FRAME_RADIUS = 44;
    private static volatile ClientLevel level;
    private static MinecraftServer server;
    private static Path file;
    private static Map<Identifier, PetConfig> definitions = Map.of();
    private static MobConfig legacyChicken;
    private static boolean loadFailed;
    private static int nextId = -1;
    private static volatile List<PlacedMob> mobs = List.of();
    private static final Map<UUID, List<PlacedMob>> stagePlacements = new HashMap<>();
    private static UUID displayedStage;
    private static final List<PlacedMob> removedPreviews = new ArrayList<>();
    private static volatile PlacedMob selected;
    private static Map<PlacementPoint, ActiveDefender> activeDefenders = Map.of();

    private PlanningMobs() {}

    public static void tick(Minecraft minecraft) {
        var defenders = minecraft.level == null ? Map.<PlacementPoint, ActiveDefender>of()
            : SpawnZones.activeDefenders(minecraft.getSingleplayerServer(), minecraft.level.dimension());
        tick(minecraft, defenders);
    }

    private static synchronized void tick(Minecraft minecraft, Map<PlacementPoint, ActiveDefender> defenders) {
        if (minecraft.level == null) {
            reset();
            return;
        }
        if (level != minecraft.level) {
            reset();
            Path path = ZoneSelection.mobPlacementFile(minecraft.level);
            if (path == null) {
                return;
            }
            level = minecraft.level;
            server = minecraft.getSingleplayerServer();
            file = path;
            try {
                definitions = PetConfig.load();
            } catch (IOException exception) {
                LogUtils.getLogger().warn("Could not load planning mob definitions", exception);
            }
            if (!definitions.containsKey(PetConfig.PLANNING_CHICKEN)) {
                legacyChicken = PetConfig.planningChicken();
            }
            load();
        }
        removedPreviews.forEach(PlanningMobs::removePreview);
        removedPreviews.clear();
        updateStagePreviews(minecraft);
        activeDefenders = defenders;
        for (PlacedMob placement : mobs) {
            if (!ZoneSelection.displaysPlacement(level, placement.scope)) {
                removePreview(placement);
                continue;
            }
            if (activeDefender(placement) != null) {
                discardPreview(placement);
                continue;
            }
            if (!placement.unavailable && (placement.entity == null || placement.entity.isRemoved())
                && level.hasChunkAt(BlockPos.containing(placement.center))) {
                Mob entity = createMob(placement);
                if (entity == null) {
                    placement.unavailable = true;
                } else {
                    spawn(placement, entity);
                }
            }
        }
    }

    private static void updateStagePreviews(Minecraft minecraft) {
        LayoutLevel stage = server == null ? StagePlanning.clientLayout(minecraft)
            : minecraft.player == null ? null : StageCopies.layout(server, level.dimension(), minecraft.player.getUUID());
        UUID scope = stage == null ? null : stage.id();
        if (Objects.equals(displayedStage, scope)) {
            return;
        }
        mobs.stream().filter(mob -> mob.temporary).forEach(PlanningMobs::removePreview);
        List<PlacedMob> updated = new ArrayList<>(mobs.stream().filter(mob -> !mob.temporary).toList());
        updated.addAll(stagePlacements.getOrDefault(scope, List.of()));
        mobs = List.copyOf(updated);
        displayedStage = scope;
    }

    static synchronized List<StageFiles.Placement> captureStagePlacements(Path path, LayoutLevel layout)
        throws IOException {
        boolean current = path.equals(file);
        if (current && loadFailed) {
            throw new IOException("Не удалось прочитать расстановку мобов исходной области.");
        }
        List<PlacedMob> placements = current ? mobs : readPlacements(path);
        Map<Vec3, StageFiles.Placement> result = new LinkedHashMap<>();
        for (PlacedMob placed : placements) {
            if (placed.temporary || (placed.scope != null && !layout.id().equals(placed.scope))) {
                continue;
            }
            var zone = ZoneSelection.placementMarkerColor(layout, placed.center, placed.zone);
            if (zone == null || (placed.zone != null && zone != placed.zone)) {
                continue;
            }
            var placement = new StageFiles.Placement(placed.id, placed.center, placed.yaw, zone);
            if (placed.scope == null) {
                result.putIfAbsent(placed.center, placement);
            } else {
                result.put(placed.center, placement);
            }
        }
        return List.copyOf(result.values());
    }

    static synchronized void addStage(UUID scope, List<StageFiles.Placement> placements, Vec3 origin) {
        stagePlacements.put(scope, placements.stream()
            .map(placement -> new PlacedMob(placement.center().add(origin), placement.yaw(), placement.id(),
                placement.zone(), scope, true)).toList());
    }

    static synchronized void removeStage(UUID scope) {
        stagePlacements.remove(scope);
        if (scope.equals(displayedStage)) {
            mobs.stream().filter(mob -> mob.temporary).forEach(removedPreviews::add);
            mobs = mobs.stream().filter(mob -> !mob.temporary).toList();
            displayedStage = null;
        }
    }

    static synchronized boolean placeStageMob(UUID scope, StageFiles.Placement placement) {
        List<PlacedMob> stage = stagePlacements.get(scope);
        if (stage == null || stage.stream().anyMatch(mob -> mob.center.distanceToSqr(placement.center()) < 1e-6)) {
            return false;
        }
        List<PlacedMob> updated = new ArrayList<>(stage);
        updated.add(new PlacedMob(placement.center(), placement.yaw(), placement.id(), placement.zone(), scope, true));
        stagePlacements.put(scope, List.copyOf(updated));
        return true;
    }

    static synchronized boolean removeStagePlacement(UUID scope, Vec3 center) {
        List<PlacedMob> stage = stagePlacements.get(scope);
        if (stage == null) {
            return false;
        }
        List<PlacedMob> updated = stage.stream().filter(mob -> !(mob.center.distanceToSqr(center) < 1e-6)).toList();
        if (updated.size() == stage.size()) {
            return false;
        }
        stagePlacements.put(scope, updated);
        return true;
    }

    static synchronized List<DefenderPlacement> defenderPlacements(ServerLevel serverLevel, UUID scope) {
        List<PlacedMob> stage = stagePlacements.get(scope);
        if (stage != null) {
            return stage.stream()
                .map(mob -> new DefenderPlacement(mob.id, mob.center, mob.yaw, mob.zone, scope, null, true)).toList();
        }
        ClientLevel client = level;
        if (client == null || server != serverLevel.getServer()
            || !client.dimension().equals(serverLevel.dimension())) {
            return List.of();
        }
        return mobs.stream().filter(mob -> Objects.equals(mob.scope, scope))
            .map(mob -> new DefenderPlacement(mob.id, mob.center, mob.yaw, mob.zone, mob.scope, file, mob.temporary))
            .toList();
    }

    record PlacementPoint(UUID scope, Vec3 center) {}

    record ActiveDefender(int entityId, AttackGrid attackGrid) {}

    record DefenderPlacement(Identifier id, Vec3 center, float yaw, ZoneSelection.ZoneColor zone, UUID scope, Path file,
                             boolean temporary) {
        boolean matches(PlacedMob mob) {
            return id.equals(mob.id) && center.equals(mob.center) && Float.compare(yaw, mob.yaw) == 0
                && zone == mob.zone && Objects.equals(scope, mob.scope);
        }
    }

    static synchronized boolean removeDefender(DefenderPlacement placement) {
        if (placement.temporary()) {
            stagePlacements.computeIfPresent(placement.scope(), (scope, placed) ->
                placed.stream().filter(mob -> !placement.matches(mob)).toList());
            mobs.stream().filter(placement::matches).forEach(removedPreviews::add);
            mobs = mobs.stream().filter(mob -> !placement.matches(mob)).toList();
            return true;
        }
        Path path = placement.file();
        boolean current = path.equals(file);
        try {
            if (current && loadFailed) {
                return false;
            }
            List<PlacedMob> placements = current ? mobs : readPlacements(path);
            List<PlacedMob> updated = placements.stream().filter(mob -> !placement.matches(mob)).toList();
            if (updated.size() != placements.size()) {
                writePlacements(path, updated);
                if (current) {
                    placements.stream().filter(placement::matches).forEach(removedPreviews::add);
                    mobs = updated;
                }
            }
            return true;
        } catch (IOException | IllegalArgumentException exception) {
            LogUtils.getLogger().error("Could not remove a fallen Layout Camera defender at {}", path, exception);
            return false;
        }
    }

    static synchronized void removeLayout(Path path, UUID scope) throws IOException {
        boolean current = path.equals(file);
        if (current && loadFailed) {
            throw new IOException("Не удалось прочитать исходную расстановку уровня.");
        }
        List<PlacedMob> placements = current ? mobs : readPlacements(path);
        List<PlacedMob> updated = placements.stream().filter(placement -> !scope.equals(placement.scope)).toList();
        if (updated.size() != placements.size()) {
            writePlacements(path, updated);
            if (current) {
                placements.stream().filter(placement -> scope.equals(placement.scope)).forEach(removedPreviews::add);
                mobs = updated;
            }
        }
    }

    public static synchronized void reset() {
        mobs.stream().filter(mob -> mob.temporary).forEach(mob -> mob.entity = null);
        displayedStage = null;
        level = null;
        server = null;
        file = null;
        definitions = Map.of();
        legacyChicken = null;
        mobs = List.of();
        removedPreviews.clear();
        selected = null;
        activeDefenders = Map.of();
        loadFailed = false;
    }

    public static void hideDeleteButton() {
        selected = null;
    }

    public static boolean isOccupied(Vec3 center, UUID scope) {
        return mobs.stream().anyMatch(placement -> Objects.equals(scope, placement.scope)
            && placement.center.distanceToSqr(center) < 1e-6);
    }

    public static boolean isPreview(Entity entity) {
        return entity.level() instanceof ClientLevel && entity.entityTags().contains(PREVIEW_TAG);
    }

    static ZoneSelection.ZoneColor allowedZone(Identifier id) throws IOException {
        if (id == null) {
            return null;
        }
        Map<Identifier, PetConfig> remote = StagePlanning.clientPets(Minecraft.getInstance());
        if (remote != null) {
            return allowedZone(id, remote.get(id));
        }
        definitions = PetConfig.load();
        mobs.forEach(placement -> placement.unavailable = false);
        return allowedZone(id, definitions.get(id));
    }

    static ZoneSelection.ZoneColor allowedZone(Identifier id, PetConfig pet) {
        if (pet == null) {
            return id.equals(PetConfig.PLANNING_CHICKEN) ? ZoneSelection.ZoneColor.BLUE : null;
        }
        return pet.canPlace() ? pet.placementZone() : null;
    }

    static AttackGrid previewAttackGrid(Minecraft minecraft, Identifier id) {
        Map<Identifier, PetConfig> remote = StagePlanning.clientPets(minecraft);
        PetConfig pet = (remote == null ? definitions : remote).get(id);
        return pet == null ? null : pet.attackGrid();
    }

    public static void drawSelectedAttackGrid(Minecraft minecraft) {
        PlacedMob placement = selected;
        if (placement != null && !PlanningDrag.active() && LayoutCamera.isActive(minecraft)
            && minecraft.level == level && minecraft.gui.screen() == null && minecraft.gui.overlay() == null) {
            var active = activeDefender(placement);
            var entity = editableMob(placement);
            var grid = active == null ? placement.attackGrid : active.attackGrid();
            if (entity != null && grid != null) {
                ZoneSelection.drawAttackGrid(grid, entity.position(), entity.getYRot());
            }
        }
    }

    private static ActiveDefender activeDefender(PlacedMob placement) {
        return activeDefenders.get(new PlacementPoint(placement.scope, placement.center));
    }

    private static Mob editableMob(PlacedMob placement) {
        var active = activeDefender(placement);
        ClientLevel clientLevel = level;
        Entity entity = active == null ? placement.entity
            : clientLevel == null ? null : clientLevel.getEntity(active.entityId());
        return entity instanceof Mob mob && mob.isAlive() ? mob : null;
    }

    public static void place(Minecraft minecraft, ZoneSelection.MobPlacement target, Identifier id) {
        tick(minecraft);
        placeMob(minecraft, target, id);
    }

    private static synchronized void placeMob(Minecraft minecraft, ZoneSelection.MobPlacement target, Identifier id) {
        if ((target.temporary() && !StageCopies.isTemporary(target.scope())) || isOccupied(target.center(), target.scope())) {
            return;
        }
        try {
            var allowed = allowedZone(id);
            if (allowed == null) {
                minecraft.player.sendOverlayMessage(Component.literal("Размещение этого моба отключено."));
                return;
            }
            if (target.color() != allowed) {
                minecraft.player.sendOverlayMessage(Component.literal("Зона для этого моба · " + allowed.zoneName()));
                return;
            }
        } catch (IOException exception) {
            minecraft.player.sendOverlayMessage(Component.literal("Не удалось прочитать mobs.json: " + exception.getMessage()));
            return;
        }
        PlacedMob placement = new PlacedMob(target.center(), target.yaw(), id, target.color(), target.scope(),
            target.temporary());
        Mob entity = createMob(placement);
        if (entity == null) {
            minecraft.player.sendOverlayMessage(Component.literal("Не удалось загрузить модель моба " + id));
            return;
        }
        List<PlacedMob> updated = new ArrayList<>(mobs);
        updated.add(placement);
        if (save(minecraft, updated, placement)) {
            spawn(placement, entity);
        }
    }

    public static void selectAtCursor(Minecraft minecraft) {
        if (PlanningDrag.active()) {
            return;
        }
        PlacedMob previous = selected;
        selected = null;
        if (LayoutCamera.isOverHint(minecraft) || LayoutCamera.hotbarSlotAtCursor(minecraft) >= 0) {
            return;
        }
        var ray = ZoneSelection.cursorRay(minecraft);
        Vec3 origin = ray.origin();
        double closest = Double.POSITIVE_INFINITY;
        PlacedMob hit = null;
        for (PlacedMob placement : mobs) {
            Mob entity = editableMob(placement);
            if (entity == null) {
                continue;
            }
            Vec3 end = origin.add(ray.direction().scale(origin.distanceTo(entity.position())
                + Math.max(entity.getBbHeight(), entity.getBbWidth()) + 2));
            var intersection = entity.getBoundingBox().inflate(0.08).clip(origin, end);
            if (intersection.isPresent()) {
                double distance = origin.distanceTo(intersection.get());
                if (distance < closest) {
                    closest = distance;
                    hit = placement;
                }
            }
        }
        if (hit != null && hit != previous) {
            Vec3 end = origin.add(ray.direction().scale(Math.max(0, closest - 0.001)));
            if (ZoneSelection.clipBlocks(minecraft, origin, end).getType() == HitResult.Type.MISS) {
                var active = activeDefender(hit);
                if (active != null) {
                    hit.attackGrid = active.attackGrid();
                } else {
                    try {
                        allowedZone(hit.id);
                        hit.attackGrid = previewAttackGrid(minecraft, hit.id);
                    } catch (IOException exception) {
                        LogUtils.getLogger().warn("Could not refresh the attack grid for {}", hit.id, exception);
                    }
                }
                selected = hit;
            }
        }
    }

    public static synchronized boolean deleteAtCursor(Minecraft minecraft) {
        PlacedMob placement = selected;
        var window = minecraft.getWindow();
        Button button = button(minecraft, placement, window.getGuiScaledWidth(), window.getGuiScaledHeight());
        if (button == null || !button.contains(minecraft.mouseHandler.getScaledXPos(window),
            minecraft.mouseHandler.getScaledYPos(window))) {
            return false;
        }
        if (placement.temporary && StagePlanning.edit(minecraft, placement.scope, placement.center, 0, -1)) {
            selected = null;
            return true;
        }
        List<PlacedMob> updated = new ArrayList<>(mobs);
        updated.remove(placement);
        if (save(minecraft, updated, placement)) {
            var active = activeDefender(placement);
            var integrated = minecraft.getSingleplayerServer();
            if (active != null && integrated != null) {
                var dimension = minecraft.level.dimension();
                var point = new PlacementPoint(placement.scope, placement.center);
                integrated.execute(() -> {
                    var serverLevel = integrated.getLevel(dimension);
                    if (serverLevel != null) {
                        SpawnZones.removeActiveDefender(serverLevel, point, active.entityId());
                    }
                });
            }
            removePreview(placement);
        }
        return true;
    }

    private static void removePreview(PlacedMob placement) {
        if (placement == selected) {
            selected = null;
        }
        discardPreview(placement);
    }

    private static void discardPreview(PlacedMob placement) {
        Mob entity = placement.entity;
        if (entity != null && !entity.isRemoved()) {
            level.removeEntity(entity.getId(), Entity.RemovalReason.DISCARDED);
        }
        placement.entity = null;
    }

    public static void drawDeleteButton(Minecraft minecraft, GuiGraphicsExtractor graphics) {
        Button button = button(minecraft, selected, graphics.guiWidth(), graphics.guiHeight());
        if (button == null) {
            return;
        }
        graphics.nextStratum();
        var pose = graphics.pose();
        pose.pushMatrix();
        pose.translate(button.anchorX(), button.anchorY());
        pose.scale(1.0f, 0.78f);
        pose.rotate((float) (Math.PI / 4));
        graphics.fill(-FRAME_RADIUS, -FRAME_RADIUS, FRAME_RADIUS, FRAME_RADIUS, 0x22fff4cc);
        drawFrame(graphics, FRAME_RADIUS - 2, 3, 0x55fff0bf);
        drawFrame(graphics, FRAME_RADIUS, 1, 0xfffaf6e6);
        pose.popMatrix();

        graphics.nextStratum();
        int x = button.x();
        int y = button.y();
        graphics.fill(x + 2, y + 3, x + DELETE_SIZE + 2, y + DELETE_SIZE + 3, 0x66000000);
        graphics.fill(x, y, x + DELETE_SIZE, y + DELETE_SIZE, 0xff131619);
        graphics.fill(x + 1, y + 1, x + DELETE_SIZE - 1, y + DELETE_SIZE - 1, 0xff202327);
        pose.pushMatrix();
        pose.translate(x + DELETE_SIZE / 2.0f, y + DELETE_SIZE / 2.0f);
        pose.rotate((float) (Math.PI / 4));
        graphics.fill(-1, -7, 1, 7, 0xffff872b);
        graphics.fill(-7, -1, -1, 1, 0xffff872b);
        graphics.fill(1, -1, 7, 1, 0xffff872b);
        pose.popMatrix();
    }

    private static void drawFrame(GuiGraphicsExtractor graphics, int radius, int thickness, int color) {
        graphics.fill(-radius, -radius, radius, -radius + thickness, color);
        graphics.fill(-radius, radius - thickness, radius, radius, color);
        graphics.fill(-radius, -radius + thickness, -radius + thickness, radius - thickness, color);
        graphics.fill(radius - thickness, -radius + thickness, radius, radius - thickness, color);
    }

    private static Button button(Minecraft minecraft, PlacedMob placement, int width, int height) {
        if (placement == null || minecraft.level != level || !LayoutCamera.isActive(minecraft)) {
            return null;
        }
        var entity = editableMob(placement);
        if (entity == null) {
            return null;
        }
        var camera = minecraft.gameRenderer.mainCamera();
        Vec3 offset = entity.position().add(0, entity.getBbHeight() * 0.8, 0).subtract(camera.position());
        if (offset.dot(new Vec3(camera.forwardVector())) <= 0) {
            return null;
        }
        Vector3f pixel = camera.getViewRotationProjectionMatrix(new Matrix4f())
            .transformProject(new Vector3f((float) offset.x, (float) offset.y, (float) offset.z));
        if (!Float.isFinite(pixel.x) || !Float.isFinite(pixel.y)
            || Math.abs(pixel.x) >= 1 || Math.abs(pixel.y) >= 1) {
            return null;
        }
        int anchorX = Math.round((pixel.x + 1) * width / 2);
        int anchorY = Math.round((1 - pixel.y) * height / 2);
        int x = Math.clamp(anchorX - 48, 0, Math.max(0, width - DELETE_SIZE));
        int y = Math.clamp(anchorY - 39, 0, Math.max(0, height - DELETE_SIZE));
        return new Button(x, y, anchorX, anchorY);
    }

    private static void load() {
        try {
            mobs = List.copyOf(readPlacements(file));
        } catch (IOException | IllegalArgumentException exception) {
            loadFailed = true;
            LogUtils.getLogger().error("Could not read Layout Camera mobs at {}", file, exception);
        }
    }

    private static List<PlacedMob> readPlacements(Path path) throws IOException {
        List<PlacedMob> loaded = new ArrayList<>();
        if (Files.notExists(path)) {
            return loaded;
        }
        for (String line : Files.readAllLines(path)) {
            String[] fields = line.split(" ");
            if (fields.length < 4 || fields.length > 7) {
                throw new IOException("Invalid mob placement record");
            }
            Vec3 center = new Vec3(Double.parseDouble(fields[0]), Double.parseDouble(fields[1]),
                Double.parseDouble(fields[2]));
            float yaw = Float.parseFloat(fields[3]);
            if (!Double.isFinite(center.x) || !Double.isFinite(center.y) || !Double.isFinite(center.z)
                || !Float.isFinite(yaw)) {
                throw new IOException("Invalid mob placement coordinates");
            }
            Identifier id = fields.length == 4 ? PetConfig.PLANNING_CHICKEN : PetConfig.petId(fields[4]);
            if (id == null) {
                throw new IOException("Invalid planning mob ID");
            }
            var zone = fields.length >= 6 ? ZoneSelection.ZoneColor.valueOf(fields[5]) : null;
            UUID scope = fields.length == 7 ? UUID.fromString(fields[6]) : null;
            loaded.add(new PlacedMob(center, yaw, id, zone, scope, false));
        }
        return loaded;
    }

    private static void writePlacements(Path path, List<PlacedMob> updated) throws IOException {
        Path temp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.createDirectories(path.getParent());
        Files.write(temp, updated.stream().filter(mob -> !mob.temporary).map(PlacedMob::encode).toList());
        Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private static boolean save(Minecraft minecraft, List<PlacedMob> updated, PlacedMob changed) {
        if (changed.temporary) {
            if (!stagePlacements.containsKey(changed.scope)) {
                return false;
            }
            stagePlacements.put(changed.scope, updated.stream().filter(mob -> changed.scope.equals(mob.scope)).toList());
            mobs = List.copyOf(updated);
            return true;
        }
        if (file != null && !loadFailed) {
            try {
                writePlacements(file, updated);
                mobs = List.copyOf(updated);
                return true;
            } catch (IOException exception) {
                LogUtils.getLogger().error("Could not save Layout Camera mobs at {}", file, exception);
            }
        }
        minecraft.player.sendOverlayMessage(Component.literal("Не удалось сохранить мобов планировки"));
        return false;
    }

    private static Mob createMob(PlacedMob placement) {
        Map<Identifier, PetConfig> remote = placement.temporary && server == null
            ? StagePlanning.clientPets(Minecraft.getInstance()) : null;
        return createMob(level, placement.id, (remote == null ? definitions : remote).get(placement.id));
    }

    static Mob createDragModel(Minecraft minecraft, Identifier id) {
        Map<Identifier, PetConfig> remote = StagePlanning.clientPets(minecraft);
        Mob entity = createMob(minecraft.level, id, (remote == null ? definitions : remote).get(id));
        if (entity != null) {
            entity.snapTo(minecraft.player.position(), 0, 0);
        }
        return entity;
    }

    private static Mob createMob(ClientLevel clientLevel, Identifier id, PetConfig pet) {
        var type = pet == null
            ? (id.equals(PetConfig.PLANNING_CHICKEN) ? EntityTypes.CHICKEN : null)
            : BuiltInRegistries.ENTITY_TYPE.getOptional(pet.model()).orElse(null);
        if (type == null || !(type.create(clientLevel, EntitySpawnReason.LOAD) instanceof Mob entity)) {
            return null;
        }
        // The HUD renderer reads the ID even for previews that never enter the level.
        while (clientLevel.getEntity(nextId) != null) {
            nextId--;
        }
        entity.setId(nextId--);
        if (pet != null) {
            Pets.configureMob(entity, pet);
        } else {
            MobConfig config = legacyChicken != null ? legacyChicken : PetConfig.planningChicken();
            entity.setCustomName(Component.literal(config.name()));
            entity.getAttribute(Attributes.MAX_HEALTH).setBaseValue(config.health());
            entity.getAttribute(Attributes.ARMOR).setBaseValue(config.armor());
            entity.setHealth(entity.getMaxHealth());
        }
        entity.setNoAi(true);
        entity.setNoGravity(true);
        entity.noPhysics = true;
        entity.setOnGround(true);
        entity.addTag(PREVIEW_TAG);
        return entity;
    }

    private static void spawn(PlacedMob placement, Mob entity) {
        entity.snapTo(placement.center, placement.yaw, 0);
        entity.yHeadRot = entity.yHeadRotO = placement.yaw;
        entity.yBodyRot = entity.yBodyRotO = placement.yaw;
        entity.setOldPosAndRot();
        level.addEntity(entity);
        placement.entity = entity;
    }

    private record Button(int x, int y, int anchorX, int anchorY) {
        boolean contains(double mouseX, double mouseY) {
            return mouseX >= x && mouseX < x + DELETE_SIZE && mouseY >= y && mouseY < y + DELETE_SIZE;
        }
    }

    private static final class PlacedMob {
        private final Vec3 center;
        private final float yaw;
        private final Identifier id;
        private final ZoneSelection.ZoneColor zone;
        private final UUID scope;
        private final boolean temporary;
        private Mob entity;
        private AttackGrid attackGrid;
        private boolean unavailable;

        PlacedMob(Vec3 center, float yaw, Identifier id, ZoneSelection.ZoneColor zone, UUID scope, boolean temporary) {
            this.center = center;
            this.yaw = yaw;
            this.id = id;
            this.zone = zone;
            this.scope = scope;
            this.temporary = temporary;
        }

        String encode() {
            String coordinates = center.x + " " + center.y + " " + center.z + " " + yaw;
            if (zone != null) {
                return coordinates + " " + id + " " + zone.name() + (scope == null ? "" : " " + scope);
            }
            return id.equals(PetConfig.PLANNING_CHICKEN) ? coordinates : coordinates + " " + id;
        }
    }
}
