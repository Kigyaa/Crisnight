package ru.layoutcamera;

import com.google.gson.JsonParser;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import ru.layoutcamera.LocalLevels.LayoutLevel;
import ru.layoutcamera.LocalLevels.Zone;
import ru.layoutcamera.LocalLevels.ZoneSettings;

public final class ZoneSelection {
    public static final String TOOL_NAME = "Выделитель зон";
    private static final GizmoStyle LEVEL_BORDER = GizmoStyle.stroke(0xffd99cff, 2.0f);
    private static volatile Selection selection = empty(null);
    private static Path zoneFile;
    private static boolean loadFailed;
    private static Path levelsFile;
    private static boolean levelsLoadFailed;
    private static String draftLevel;
    private static BlockPos draftCorner;

    private ZoneSelection() {}

    public static boolean isHoldingTool(Minecraft minecraft) {
        if (minecraft.player == null || minecraft.player.isSpectator()) {
            return false;
        }
        var stack = minecraft.player.getMainHandItem();
        Component name = stack.get(DataComponents.CUSTOM_NAME);
        return stack.getItem() == Items.STICK && name != null && TOOL_NAME.equals(name.getString());
    }

    public static synchronized void switchZone(Minecraft minecraft) {
        if (LayoutCamera.isActive(minecraft)) {
            return;
        }
        Selection current = forLevel(minecraft);
        Selection next = new Selection(current.level(), current.server(), current.zones(),
            current.starts(), current.active().next(), current.layouts());
        selection = next;
        message(minecraft, next, "выбери угол левой кнопкой мыши");
    }

    public static synchronized void selectCorner(Minecraft minecraft) {
        if (LayoutCamera.isActive(minecraft)) {
            return;
        }
        Selection current = forLevel(minecraft);
        HitResult hit = minecraft.hitResult;
        if (!(hit instanceof BlockHitResult block) || hit.getType() != HitResult.Type.BLOCK) {
            message(minecraft, current, "наведи на блок");
            return;
        }

        BlockPos position = block.getBlockPos().immutable();
        if (draftLevel != null) {
            selectLevelCorner(minecraft, current, position);
            return;
        }
        ZoneColor color = current.active();
        BlockPos start = current.starts().get(color);
        LayoutLevel layout = layoutAt(current, Vec3.atCenterOf(position));
        if (layout != null && layout == temporaryLayout(current)) {
            message(minecraft, current, "настрой зоны в исходной области перед первым сохранением");
            clearStart(current, color);
            return;
        }
        List<Zone> zones = new ArrayList<>(layout == null ? current.zones() : layout.zones().values());
        Zone clicked = findZone(zones, position);
        boolean deleting = start == null && clicked != null && clicked.color() == color;
        if (deleting) {
            zones.remove(clicked);
        } else if (start != null) {
            AABB box = bounds(start, position);
            if (layout == null ? layoutsForDisplay(current).stream().anyMatch(level -> level.bounds().intersects(box))
                : !LocalLevels.contains(layout.bounds(), box)) {
                message(minecraft, current, "оба угла зоны должны находиться в одном уровне или за пределами уровней");
                clearStart(current, color);
                return;
            }
            zones.add(layout == null ? new Zone(null, box, color, null)
                : new Zone(UUID.randomUUID(), box, color, ZoneSettings.read(color, new JsonObject())));
        }

        if (layout == null && (deleting || start != null) && !save(zones)) {
            message(minecraft, current, "не удалось сохранить зоны");
            return;
        }
        if (layout != null && (deleting || start != null)) {
            try {
                Map<UUID, Zone> updated = new LinkedHashMap<>();
                zones.forEach(zone -> updated.put(zone.id(), zone));
                updateLayout(current, layout, new LayoutLevel(layout.id(), layout.bounds(), updated));
                current = selection;
            } catch (IOException exception) {
                message(minecraft, current, exception.getMessage());
                return;
            }
        }
        Map<ZoneColor, BlockPos> starts = new EnumMap<>(ZoneColor.class);
        starts.putAll(current.starts());
        if (deleting || start != null) {
            starts.remove(color);
        } else {
            starts.put(color, position);
        }
        selection = new Selection(current.level(), current.server(),
            layout == null ? List.copyOf(zones) : current.zones(), Map.copyOf(starts), color, current.layouts());
        message(minecraft, selection, deleting ? "область удалена"
            : start != null ? "область выделена" : "выбран первый угол");
    }

    private static void clearStart(Selection current, ZoneColor color) {
        Map<ZoneColor, BlockPos> starts = new EnumMap<>(ZoneColor.class);
        starts.putAll(current.starts());
        starts.remove(color);
        selection = new Selection(current.level(), current.server(), current.zones(), Map.copyOf(starts), color,
            current.layouts());
    }

    public static void draw(Minecraft minecraft) {
        Selection current = selection;
        if (current.level() == null || current.level() != minecraft.level) {
            return;
        }

        if (LayoutCamera.isActive(minecraft)) {
            if (minecraft.player == null || minecraft.gui.screen() != null || minecraft.gui.overlay() != null) {
                return;
            }
            LayoutLevel temporary = planningCopy(minecraft);
            LayoutLevel layout = temporary == null ? draftAt(current, minecraft.player.position()) : temporary;
            UUID scope = layout == null ? null : layout.id();
            List<Zone> zones = activeZones(current, layout);
            if (temporary != null) {
                drawCopyZones(minecraft, zones, scope);
                return;
            }
            ZoneColor allowedColor = PlanningDrag.placementColor();
            if (allowedColor != null) {
                for (Zone zone : zones) {
                    boolean available = isPlacementSpot(zone, allowedColor, PlanningDrag.placementId(), scope);
                    if (available || isRouteZone(zone)) {
                        drawZone(zone, List.of(), available);
                    }
                }
            } else if (minecraft.gui.hud.isHidden() || (!LayoutCamera.isOverHint(minecraft)
                && LayoutCamera.hotbarSlotAtCursor(minecraft) < 0)) {
                Zone hovered = hoveredZone(minecraft, zones, zone -> true);
                if (hovered != null) {
                    drawZone(hovered, List.of(), true);
                }
            }
            return;
        }

        List<Zone> zones = current.zones();
        for (int index = 0; index < zones.size(); index++) {
            drawZone(zones.get(index), zones.subList(index + 1, zones.size()), true);
        }
        for (LayoutLevel layout : current.layouts().values()) {
            Gizmos.cuboid(layout.bounds(), LEVEL_BORDER);
            List<Zone> localZones = List.copyOf(layout.zones().values());
            for (int index = 0; index < localZones.size(); index++) {
                drawZone(localZones.get(index), localZones.subList(index + 1, localZones.size()), true);
            }
        }
        if (draftCorner != null) {
            Gizmos.cuboid(draftCorner, LEVEL_BORDER);
        }
        current.starts().forEach((color, start) -> Gizmos.cuboid(start, color.style));
    }

    private static boolean isRouteZone(Zone zone) {
        return zone.color() == ZoneColor.RED || zone.color() == ZoneColor.WHITE;
    }

    private static void drawCopyZones(Minecraft minecraft, List<Zone> zones, UUID scope) {
        ZoneColor allowed = PlanningDrag.placementColor();
        Identifier id = PlanningDrag.placementId();
        boolean overHud = !minecraft.gui.hud.isHidden() && (LayoutCamera.isOverHint(minecraft)
            || LayoutCamera.hotbarSlotAtCursor(minecraft) >= 0);
        Zone hovered = allowed == null && !overHud
            ? hoveredCopyZone(minecraft, cursorRay(minecraft), zones, zone -> !isRouteZone(zone)) : null;
        for (Zone zone : zones) {
            boolean available = allowed != null && isPlacementSpot(zone, allowed, id, scope);
            if (isRouteZone(zone) || available || zone == hovered) {
                AABB box = zone.bounds();
                double y = box.maxY + 0.035;
                Gizmos.rect(new Vec3(box.minX, y, box.minZ), new Vec3(box.maxX, y, box.maxZ),
                    Direction.UP, zone.color().style);
                if (available || (zone == hovered && hasMarker(box))) {
                    Gizmos.point(markerCenter(box), 0xffff3535, 5.0f);
                }
            }
        }
    }

    public static void removeMobsOnWhiteZones(ServerLevel level) {
        Selection current = selection;
        if (matches(current, level)) {
            removeMobsOnWhiteZones(level, current.zones(), null);
            for (LayoutLevel layout : current.layouts().values()) {
                removeMobsOnWhiteZones(level, activeZones(current, layout), layout.id());
            }
        }
        for (LayoutLevel temporary : StageCopies.layouts(level)) {
            removeMobsOnWhiteZones(level, activeZones(current, temporary), temporary.id());
        }
    }

    private static void removeMobsOnWhiteZones(ServerLevel level, List<Zone> zones, UUID scope) {
        for (Zone zone : zones) {
            if (zone.color() != ZoneColor.WHITE) {
                continue;
            }
            AABB box = zone.bounds();
            AABB search = new AABB(box.minX, box.minY, box.minZ, box.maxX, box.maxY + 2, box.maxZ);
            for (Mob mob : level.getEntitiesOfClass(Mob.class, search, candidate ->
                candidate.isAlive() && LocalLevels.belongsTo(candidate, scope) && candidate.onGround()
                    && box.contains(Vec3.atCenterOf(candidate.getOnPosLegacy())))) {
                mob.discard();
            }
        }
    }

    static List<AABB> zonesOfColor(ServerLevel level, ZoneColor color) {
        Selection current = selection;
        if (!matches(current, level)) {
            return List.of();
        }
        return current.zones().stream().filter(zone -> zone.color() == color).map(Zone::bounds)
            .filter(box -> box.getXsize() >= 1 && box.getYsize() >= 1 && box.getZsize() >= 1).toList();
    }

    static LayoutLevel layoutAt(ServerLevel level, UUID owner, Vec3 position) {
        Selection current = selection;
        LayoutLevel temporary = StageCopies.layout(level.getServer(), level.dimension(), owner);
        if (temporary != null && insideLayout(temporary, position)) {
            return temporary;
        }
        return matches(current, level) ? draftAt(current, position) : null;
    }

    private static LayoutLevel layoutAt(Selection current, Vec3 position) {
        LayoutLevel temporary = temporaryLayout(current);
        if (temporary != null && insideLayout(temporary, position)) {
            return temporary;
        }
        return draftAt(current, position);
    }

    private static LayoutLevel draftAt(Selection current, Vec3 position) {
        for (LayoutLevel layout : current.layouts().values()) {
            if (layout.bounds().contains(position)) {
                return layout;
            }
        }
        Vec3 below = position.add(0, -0.05, 0);
        for (LayoutLevel layout : current.layouts().values()) {
            if (layout.bounds().contains(below)) {
                return layout;
            }
        }
        return null;
    }

    static boolean insideLayout(LayoutLevel layout, Vec3 position) {
        return layout.bounds().contains(position) || layout.bounds().contains(position.add(0, -0.05, 0));
    }

    private static LayoutLevel planningCopy(Minecraft minecraft) {
        Selection current = selection;
        if (minecraft.player == null || current.level() != minecraft.level) {
            return null;
        }
        LayoutLevel layout = temporaryLayout(current);
        return layout != null && LocalLevels.isNear(layout.bounds(), minecraft.player.position(), 20) ? layout : null;
    }

    private static LayoutLevel temporaryLayout(Selection current) {
        if (current.level() == null) {
            return null;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (current.server() == null) {
            return StagePlanning.clientLayout(minecraft);
        }
        var player = minecraft.player;
        return player == null ? null : StageCopies.layout(current.server(), current.level().dimension(), player.getUUID());
    }

    private static List<LayoutLevel> layoutsForDisplay(Selection current) {
        List<LayoutLevel> layouts = new ArrayList<>(current.layouts().values());
        LayoutLevel temporary = temporaryLayout(current);
        if (temporary != null) {
            layouts.add(temporary);
        }
        return layouts;
    }

    static LayoutLevel draftFor(ServerLevel level, String name) {
        Selection current = selection;
        LayoutLevel draft = matches(current, level) ? current.layouts().get(name) : null;
        return draft == null ? null : includeLegacyZones(draft, current.zones());
    }

    static Path sourcePlacementFile(ServerLevel level) {
        String world = "world:" + level.getServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        return placementPath(zonePath(Minecraft.getInstance(), world, level.dimension().identifier()));
    }

    static ZoneColor placementMarkerColor(LayoutLevel layout, Vec3 center, ZoneColor preferred) {
        ZoneColor fallback = null;
        for (Zone zone : new ArrayList<>(layout.zones().values()).reversed()) {
            if (hasMarker(zone.bounds()) && markerCenter(zone.bounds()).distanceToSqr(center) < 1e-6) {
                if (zone.color() == preferred) {
                    return preferred;
                }
                if (fallback == null) {
                    fallback = zone.color();
                }
            }
        }
        return fallback;
    }

    static boolean permitsStagePlacement(LayoutLevel layout, Vec3 center, ZoneColor color, Identifier mob) {
        return layout.zones().values().stream().anyMatch(zone -> zone.color() == color
            && zone.settings().permits(mob) && hasMarker(zone.bounds())
            && markerCenter(zone.bounds()).distanceToSqr(center) < 1e-6);
    }

    static LayoutLevel includeLegacyZones(LayoutLevel draft, List<Zone> legacyZones) {
        Map<UUID, Zone> zones = new LinkedHashMap<>();
        for (Zone zone : legacyZones) {
            AABB box = zone.bounds();
            if (!LocalLevels.contains(draft.bounds(), box) || draft.zones().values().stream()
                .anyMatch(existing -> existing.color() == zone.color() && existing.bounds().equals(box))) {
                continue;
            }
            String key = draft.id() + "|" + zone.color().name() + "|" + box;
            UUID id = UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
            zones.put(id, new Zone(id, box, zone.color(), ZoneSettings.read(zone.color(), new JsonObject())));
        }
        zones.putAll(draft.zones());
        return new LayoutLevel(draft.id(), draft.bounds(), zones);
    }

    static boolean canPlaceTemporary(ServerLevel level, AABB bounds) {
        Selection current = selection;
        return !matches(current, level) || current.layouts().values().stream().noneMatch(layout -> layout.bounds().intersects(bounds))
            && current.zones().stream().noneMatch(zone -> zone.bounds().intersects(bounds));
    }

    private static boolean matches(Selection current, ServerLevel level) {
        return current.level() != null && current.server() == level.getServer()
            && level.dimension().equals(current.level().dimension());
    }

    private static List<Zone> activeZones(Selection current, LayoutLevel layout) {
        return layout == null ? current.zones()
            : layout.zones().values().stream().filter(zone -> zone.settings().enabled()).toList();
    }

    static boolean displaysPlacement(ClientLevel level, UUID scope) {
        Selection current = selection;
        if (current.level() != level) {
            return false;
        }
        if (scope == null) {
            return true;
        }
        LayoutLevel temporary = temporaryLayout(current);
        return (temporary != null && scope.equals(temporary.id()))
            || current.layouts().values().stream().anyMatch(layout -> scope.equals(layout.id()));
    }

    static ZoneColor placementColorAt(ServerLevel level, LayoutLevel layout, Vec3 center, ZoneColor preferred,
                                      Identifier mob) {
        Selection current = selection;
        if (layout == null && !matches(current, level)) {
            return null;
        }
        ZoneColor fallback = null;
        for (Zone zone : activeZones(current, layout).reversed()) {
            if (layout != null && (!zone.settings().permits(mob) || zone.color() != preferred)) {
                continue;
            }
            if (hasMarker(zone.bounds()) && markerCenter(zone.bounds()).distanceToSqr(center) < 1e-6) {
                if (zone.color() == preferred) {
                    return preferred;
                }
                if (fallback == null) {
                    fallback = zone.color();
                }
            }
        }
        return fallback;
    }

    private static Zone findZone(List<Zone> zones, BlockPos position) {
        Vec3 center = Vec3.atCenterOf(position);
        for (Zone zone : zones.reversed()) {
            if (zone.bounds().contains(center)) {
                return zone;
            }
        }
        return null;
    }

    public static synchronized void reset() {
        selection = empty(null);
        zoneFile = null;
        loadFailed = false;
        levelsFile = null;
        levelsLoadFailed = false;
        cancelLevelDraft();
    }

    public static synchronized void loadLevel(Minecraft minecraft) {
        ClientLevel level = minecraft.level;
        if (level == null || selection.level() == level) {
            return;
        }

        var server = minecraft.getSingleplayerServer();
        var remote = minecraft.getCurrentServer();
        var connection = minecraft.getConnection();
        if ((server == null && remote == null && connection == null) || (server != null && minecraft.player == null)) {
            return;
        }

        reset();
        String world = server != null ? "world:" + server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize()
            : remote != null ? "server:" + remote.ip
            : "connection:" + connection.getConnection().getRemoteAddress();
        zoneFile = zonePath(minecraft, world, level.dimension().identifier());
        Map<String, LayoutLevel> layouts = Map.of();
        if (server != null && server.isSingleplayerOwner(minecraft.player.nameAndId())) {
            levelsFile = levelsPath(zoneFile, minecraft.player.getUUID());
            try {
                layouts = LocalLevels.load(levelsFile);
            } catch (IOException exception) {
                levelsLoadFailed = true;
                LogUtils.getLogger().error("Could not read Layout Camera levels at {}", levelsFile, exception);
                minecraft.player.sendSystemMessage(Component.literal(exception.getMessage()));
            }
        }
        loadFailed = false;
        try {
            List<Zone> zones = new ArrayList<>();
            if (Files.exists(zoneFile)) {
                for (String line : Files.readAllLines(zoneFile)) {
                    String[] fields = line.split(" ");
                    if (fields.length != 7) {
                        throw new IOException("Invalid zone record");
                    }
                    AABB box = new AABB(Integer.parseInt(fields[1]), Integer.parseInt(fields[2]),
                        Integer.parseInt(fields[3]), Integer.parseInt(fields[4]),
                        Integer.parseInt(fields[5]), Integer.parseInt(fields[6]));
                    zones.add(new Zone(null, box, ZoneColor.valueOf(fields[0].toUpperCase(Locale.ROOT)), null));
                }
            }
            selection = new Selection(level, server, List.copyOf(zones), Map.of(), ZoneColor.BLUE, layouts);
        } catch (IOException | IllegalArgumentException exception) {
            loadFailed = true;
            selection = new Selection(level, server, List.of(), Map.of(), ZoneColor.BLUE, layouts);
            LogUtils.getLogger().error("Could not read Layout Camera zones at {}", zoneFile, exception);
        }
    }

    private static boolean save(List<Zone> zones) {
        if (zoneFile == null || loadFailed) {
            return false;
        }
        List<String> lines = new ArrayList<>(zones.size());
        for (Zone zone : zones) {
            AABB box = zone.bounds();
            lines.add(zone.color().name().toLowerCase(Locale.ROOT) + " " + (int) box.minX + " " + (int) box.minY
                + " " + (int) box.minZ + " " + (int) box.maxX + " " + (int) box.maxY + " " + (int) box.maxZ);
        }
        Path temp = zoneFile.resolveSibling(zoneFile.getFileName() + ".tmp");
        try {
            Files.createDirectories(zoneFile.getParent());
            Files.write(temp, lines);
            Files.move(temp, zoneFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException exception) {
            LogUtils.getLogger().error("Could not save Layout Camera zones at {}", zoneFile, exception);
            return false;
        }
    }

    private static Selection forLevel(Minecraft minecraft) {
        loadLevel(minecraft);
        return selection;
    }

    private static Selection empty(ClientLevel level) {
        return new Selection(level, null, List.of(), Map.of(), ZoneColor.BLUE, Map.of());
    }

    static synchronized void beginLevelDraft(Minecraft minecraft, String name) {
        LocalLevels.checkName(name);
        if (levelsFile == null) {
            throw new IllegalArgumentException("Уровни доступны хозяину одиночного мира или мира по LAN.");
        }
        if (levelsLoadFailed) {
            throw new IllegalArgumentException("Исправь файл уровней и выполни /layoutlevel reload.");
        }
        if (selection.layouts().containsKey(name)) {
            throw new IllegalArgumentException("Уже есть уровень " + name + ".");
        }
        draftLevel = name;
        draftCorner = null;
        Selection current = selection;
        selection = new Selection(current.level(), current.server(), current.zones(), Map.of(), current.active(),
            current.layouts());
        tell(minecraft, "Уровень " + name + " · выйди из планирования и отметь палкой два угла объёма.");
    }

    static synchronized void cancelLevelDraft() {
        draftLevel = null;
        draftCorner = null;
    }

    private static void selectLevelCorner(Minecraft minecraft, Selection current, BlockPos position) {
        if (draftCorner == null) {
            draftCorner = position;
            message(minecraft, current, "выбран первый угол уровня " + draftLevel);
            return;
        }
        AABB area = bounds(draftCorner, position);
        if (layoutsForDisplay(current).stream().anyMatch(level -> level.bounds().intersects(area))) {
            draftCorner = null;
            message(minecraft, current, "границы уровней пересеклись · выбери два угла заново");
            return;
        }
        try {
            Map<String, LayoutLevel> layouts = new LinkedHashMap<>(current.layouts());
            layouts.put(draftLevel, new LayoutLevel(UUID.randomUUID(), area, Map.of()));
            saveLayouts(current, layouts);
            tell(minecraft, "Граница " + draftLevel + " отмечена. Выдели зоны и введи /createstage " + draftLevel + ".");
            cancelLevelDraft();
        } catch (IOException exception) {
            tell(minecraft, exception.getMessage());
        }
    }

    static void listLayouts(Minecraft minecraft) {
        tell(minecraft, "Файл уровней: " + levelsFile);
        tell(minecraft, selection.layouts().isEmpty() ? "В этом измерении пока нет уровней."
            : "Уровни: " + String.join(", ", selection.layouts().keySet()));
    }

    static void listZones(Minecraft minecraft, String name) {
        var zones = findLayout(name).zones();
        if (zones.isEmpty()) {
            tell(minecraft, "Уровень " + name + " пока без зон.");
            return;
        }
        zones.values().forEach(zone -> {
            AABB box = zone.bounds();
            String coordinates = " · %s · (%d %d %d)–(%d %d %d) · %s".formatted(zone.color().zoneName(),
                (int) box.minX, (int) box.minY, (int) box.minZ, (int) box.maxX - 1, (int) box.maxY - 1,
                (int) box.maxZ - 1, zone.settings().json());
            minecraft.player.sendSystemMessage(Component.literal(zone.id().toString())
                .withStyle(style -> style.withClickEvent(new ClickEvent.CopyToClipboard(zone.id().toString())))
                .append(coordinates));
        });
    }

    static synchronized void configureZone(Minecraft minecraft, String levelName, String zoneId, String json) throws IOException {
        Selection current = selection;
        LayoutLevel layout = findLayout(levelName);
        UUID id = UUID.fromString(zoneId);
        Zone zone = layout.zones().get(id);
        if (zone == null) {
            throw new IllegalArgumentException("В уровне " + levelName + " нет зоны " + zoneId + ".");
        }
        ZoneSettings settings = ZoneSettings.read(zone.color(), JsonParser.parseString(json).getAsJsonObject());
        Map<UUID, Zone> zones = new LinkedHashMap<>(layout.zones());
        zones.put(id, new Zone(id, zone.bounds(), zone.color(), settings));
        updateLayout(current, layout, new LayoutLevel(layout.id(), layout.bounds(), zones));
        tell(minecraft, "Настройки зоны " + id + " сохранены.");
    }

    static synchronized void reloadLayouts(Minecraft minecraft) throws IOException {
        if (levelsFile == null) {
            throw new IOException("Файл уровней недоступен.");
        }
        try {
            applyLayouts(selection, LocalLevels.load(levelsFile));
        } catch (IOException exception) {
            levelsLoadFailed = true;
            throw exception;
        }
        levelsLoadFailed = false;
        cancelLevelDraft();
        listLayouts(minecraft);
    }

    static synchronized boolean deleteStageDraft(ServerLevel commandLevel, UUID owner, String name, UUID sourceId)
        throws IOException {
        Minecraft minecraft = Minecraft.getInstance();
        MinecraftServer server = commandLevel.getServer();
        String world = "world:" + server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        var candidates = sourceId == null ? List.of(commandLevel) : server.getAllLevels();
        for (ServerLevel level : candidates) {
            Path zones = zonePath(minecraft, world, level.dimension().identifier());
            Path file = levelsPath(zones, owner);
            Map<String, LayoutLevel> layouts = LocalLevels.load(file);
            LayoutLevel layout = layouts.get(name);
            if (layout == null || (sourceId != null && !sourceId.equals(layout.id()))) {
                continue;
            }
            PlanningMobs.removeLayout(placementPath(zones), layout.id());
            Map<String, LayoutLevel> updated = new LinkedHashMap<>(layouts);
            updated.remove(name);
            LocalLevels.save(file, updated);
            if (file.equals(levelsFile)) {
                applyLayouts(selection, updated);
                if (name.equals(draftLevel)) {
                    cancelLevelDraft();
                }
            }
            SpawnZones.cancel(level, layout.id());
            return true;
        }
        return false;
    }

    private static Path zonePath(Minecraft minecraft, String world, Identifier dimension) {
        String key = world + "|" + dimension;
        String file = UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)) + ".txt";
        return minecraft.gameDirectory.toPath().resolve("config/layout-camera/zones").resolve(file);
    }

    private static Path levelsPath(Path zones, UUID owner) {
        return zones.resolveSibling(zones.getFileName() + "." + owner + ".levels.json");
    }

    private static LayoutLevel findLayout(String name) {
        LayoutLevel layout = selection.layouts().get(name);
        if (layout == null) {
            throw new IllegalArgumentException("Неизвестный уровень: " + name);
        }
        return layout;
    }

    private static void updateLayout(Selection current, LayoutLevel original, LayoutLevel updated) throws IOException {
        Map<String, LayoutLevel> layouts = new LinkedHashMap<>(current.layouts());
        layouts.replaceAll((name, layout) -> layout == original ? updated : layout);
        saveLayouts(current, layouts);
    }

    private static void saveLayouts(Selection current, Map<String, LayoutLevel> layouts) throws IOException {
        if (levelsFile == null || levelsLoadFailed) {
            throw new IOException("Не удалось сохранить уровни. Проверь файл и выполни /layoutlevel reload.");
        }
        LocalLevels.save(levelsFile, layouts);
        applyLayouts(current, layouts);
    }

    private static void applyLayouts(Selection current, Map<String, LayoutLevel> layouts) {
        selection = new Selection(current.level(), current.server(), current.zones(), current.starts(), current.active(),
            LocalLevels.orderedCopy(layouts));
    }

    static void tell(Minecraft minecraft, String text) {
        minecraft.player.sendSystemMessage(Component.literal(text));
    }

    static MobPlacement mobPlacement(Minecraft minecraft, ZoneColor allowedColor, Identifier id) {
        Selection current = selection;
        if (current.level() != minecraft.level || minecraft.level == null || minecraft.player == null
            || (!minecraft.gui.hud.isHidden() && (LayoutCamera.isOverHint(minecraft)
                || LayoutCamera.hotbarSlotAtCursor(minecraft) >= 0))) {
            return null;
        }
        LayoutLevel temporary = planningCopy(minecraft);
        LayoutLevel layout = temporary == null ? draftAt(current, minecraft.player.position()) : temporary;
        UUID scope = layout == null ? null : layout.id();
        List<Zone> zones = activeZones(current, layout);
        CursorRay ray = cursorRay(minecraft);
        Predicate<Zone> filter = candidate -> isPlacementSpot(candidate, allowedColor, id, scope);
        Zone zone = temporary == null ? hoveredZone(minecraft, zones, filter)
            : hoveredCopyZone(minecraft, ray, zones, filter);
        if (zone == null) {
            return null;
        }
        AABB box = zone.bounds();
        Vec3 center = markerCenter(box);
        Vec3 hit = markerHit(ray, box);
        if (hit == null) {
            return null;
        }
        Vec3 offset = hit.subtract(center);
        float yaw = offset.horizontalDistanceSqr() < 1e-8
            ? LayoutCamera.yaw() : (float) Math.toDegrees(Math.atan2(-offset.x, offset.z));
        return new MobPlacement(center, 90.0f * Math.floorMod(Math.round(yaw / 90.0f), 4), zone.color(), scope,
            temporary != null);
    }

    static Vec3 markerHit(CursorRay ray, AABB box) {
        if (ray.direction().y >= -1e-6) {
            return null;
        }
        double distance = (box.maxY + 0.025 - ray.origin().y) / ray.direction().y;
        if (distance <= 0) {
            return null;
        }
        Vec3 hit = ray.origin().add(ray.direction().scale(distance));
        return box.contains(hit.x, box.maxY - 0.01, hit.z) ? hit : null;
    }

    private static Zone hoveredCopyZone(Minecraft minecraft, CursorRay ray, List<Zone> zones, Predicate<Zone> filter) {
        Zone nearest = null;
        Vec3 nearestHit = null;
        double nearestDistance = Double.POSITIVE_INFINITY;
        for (Zone zone : zones.reversed()) {
            Vec3 hit = filter.test(zone) ? markerHit(ray, zone.bounds()) : null;
            if (hit != null) {
                double distance = hit.distanceToSqr(ray.origin());
                if (distance < nearestDistance) {
                    nearest = zone;
                    nearestHit = hit;
                    nearestDistance = distance;
                }
            }
        }
        return nearestHit != null && clipBlocks(minecraft, ray.origin(), nearestHit).getType() == HitResult.Type.MISS
            ? nearest : null;
    }

    private static Zone hoveredZone(Minecraft minecraft, List<Zone> zones, Predicate<Zone> filter) {
        CursorRay ray = cursorRay(minecraft);
        Vec3 from = ray.origin();
        Zone nearest = null;
        double nearestDistance = Double.POSITIVE_INFINITY;
        for (Zone zone : zones.reversed()) {
            if (!filter.test(zone)) {
                continue;
            }
            AABB box = zone.bounds();
            if (box.contains(from)) {
                return zone;
            }
            // Reach beyond this box regardless of how far it is from the camera.
            double reach = from.distanceTo(box.getCenter()) + box.getXsize() + box.getYsize() + box.getZsize();
            Vec3 end = from.add(ray.direction().scale(reach));
            var intersection = box.clip(from, end);
            if (intersection.isPresent()) {
                double distance = from.distanceTo(intersection.get());
                if (distance < nearestDistance - 1e-6) {
                    nearest = zone;
                    nearestDistance = distance;
                }
            }
        }
        if (nearest == null) {
            return null;
        }
        Vec3 entry = from.add(ray.direction().scale(Math.max(0.0, nearestDistance - 0.001)));
        return clipBlocks(minecraft, from, entry).getType() == HitResult.Type.MISS ? nearest : null;
    }

    static CursorRay cursorRay(Minecraft minecraft) {
        Camera camera = minecraft.gameRenderer.mainCamera();
        var window = minecraft.getWindow();
        double x = minecraft.mouseHandler.getScaledXPos(window);
        double y = minecraft.mouseHandler.getScaledYPos(window);
        float ndcX = (float) (2 * x / window.getGuiScaledWidth() - 1);
        float ndcY = (float) (1 - 2 * y / window.getGuiScaledHeight());
        Matrix4f inverse = camera.getViewRotationProjectionMatrix(new Matrix4f()).invert();
        Vector3f ray = inverse.transformProject(new Vector3f(ndcX, ndcY, 0.5f)).normalize();
        return new CursorRay(camera.position(), new Vec3(ray));
    }

    static HitResult clipBlocks(Minecraft minecraft, Vec3 from, Vec3 to) {
        return minecraft.level.clip(new ClipContext(from, to, ClipContext.Block.OUTLINE,
            ClipContext.Fluid.NONE, minecraft.player));
    }

    static Path mobPlacementFile(ClientLevel level) {
        return zoneFile != null && selection.level() == level
            ? placementPath(zoneFile) : null;
    }

    private static Path placementPath(Path zones) {
        return zones.resolveSibling(zones.getFileName() + ".chickens");
    }

    private static void drawZone(Zone zone, List<Zone> coveringZones, boolean showMarker) {
        AABB box = zone.bounds();
        GizmoStyle style = zone.color().style;
        List<AABB> pieces = List.of(box);
        for (Zone cover : coveringZones) {
            if (!box.intersects(cover.bounds())) {
                continue;
            }
            List<AABB> remainder = new ArrayList<>();
            for (AABB piece : pieces) {
                subtract(piece, cover.bounds(), remainder);
            }
            pieces = remainder;
            if (pieces.isEmpty()) {
                return;
            }
        }
        for (AABB piece : pieces) {
            Gizmos.cuboid(piece.inflate(0.006), style);
        }

        if (showMarker && hasMarker(box)) {
            Vec3 center = box.getCenter();
            if (pieces.stream().anyMatch(piece -> piece.contains(center))) {
                Gizmos.point(markerCenter(box), 0xffff3535, 5.0f);
            }
        }
    }

    private static boolean hasMarker(AABB box) {
        return box.getXsize() == 2 && box.getZsize() == 2 && box.getYsize() == 1;
    }

    private static boolean isPlacementSpot(Zone zone, ZoneColor allowedColor, Identifier id, UUID scope) {
        return zone.color() == allowedColor && hasMarker(zone.bounds())
            && (zone.settings() == null || zone.settings().permits(id))
            && !PlanningMobs.isOccupied(markerCenter(zone.bounds()), scope);
    }

    private static Vec3 markerCenter(AABB box) {
        return new Vec3((box.minX + box.maxX) / 2, box.maxY + 0.025, (box.minZ + box.maxZ) / 2);
    }

    private static void subtract(AABB box, AABB cover, List<AABB> remainder) {
        if (!box.intersects(cover)) {
            remainder.add(box);
            return;
        }
        AABB overlap = box.intersect(cover);
        // Split the remainder into disjoint boxes around the intersection.
        addPiece(remainder, box.minX, box.minY, box.minZ, overlap.minX, box.maxY, box.maxZ);
        addPiece(remainder, overlap.maxX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
        addPiece(remainder, overlap.minX, box.minY, box.minZ, overlap.maxX, overlap.minY, box.maxZ);
        addPiece(remainder, overlap.minX, overlap.maxY, box.minZ, overlap.maxX, box.maxY, box.maxZ);
        addPiece(remainder, overlap.minX, overlap.minY, box.minZ, overlap.maxX, overlap.maxY, overlap.minZ);
        addPiece(remainder, overlap.minX, overlap.minY, overlap.maxZ, overlap.maxX, overlap.maxY, box.maxZ);
    }

    private static void addPiece(List<AABB> remainder, double minX, double minY, double minZ,
                                 double maxX, double maxY, double maxZ) {
        if (minX < maxX && minY < maxY && minZ < maxZ) {
            remainder.add(new AABB(minX, minY, minZ, maxX, maxY, maxZ));
        }
    }

    private static void message(Minecraft minecraft, Selection current, String message) {
        minecraft.player.sendOverlayMessage(
            Component.literal(current.active().zoneName() + " · " + message));
    }

    private static AABB bounds(BlockPos first, BlockPos second) {
        return new AABB(
            Math.min(first.getX(), second.getX()), Math.min(first.getY(), second.getY()), Math.min(first.getZ(), second.getZ()),
            Math.max(first.getX(), second.getX()) + 1.0, Math.max(first.getY(), second.getY()) + 1.0,
            Math.max(first.getZ(), second.getZ()) + 1.0);
    }

    enum ZoneColor {
        BLUE("Верхняя зона", 0x338cff),
        GREEN("Нижняя зона", 0x36d766),
        RED("Зона спавна", 0xff3535),
        WHITE("Зона финиша", 0xffffff);

        private final String label;
        private final GizmoStyle style;

        ZoneColor(String label, int rgb) {
            this.label = label;
            this.style = GizmoStyle.strokeAndFill(0xff000000 | rgb, 2.0f, 0x34000000 | rgb);
        }

        private ZoneColor next() {
            ZoneColor[] colors = values();
            return colors[(ordinal() + 1) % colors.length];
        }

        String zoneName() {
            return label;
        }
    }

    record MobPlacement(Vec3 center, float yaw, ZoneColor color, UUID scope, boolean temporary) {}
    record CursorRay(Vec3 origin, Vec3 direction) {}
    private record Selection(ClientLevel level, MinecraftServer server, List<Zone> zones,
                             Map<ZoneColor, BlockPos> starts, ZoneColor active, Map<String, LayoutLevel> layouts) {}
}
