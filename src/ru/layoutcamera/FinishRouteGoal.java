package ru.layoutcamera;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

public final class FinishRouteGoal extends Goal {
    private static final String ROUTE_TAG = "layoutcamera_finish=";
    private static final String POINTS_TAG = "layoutcamera_waypoints=";
    private static final String STEP_TAG = "layoutcamera_route_step=";
    private static final AttributeModifier WAVE_SPEED = new AttributeModifier(
        Identifier.fromNamespaceAndPath("layoutcamera", "wave_speed"), -0.5,
        AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
    private final Mob mob;
    private final BlockPos finish;
    private final FactionCombat combat;
    private List<BlockPos> points;
    private int step;
    private int retryTicks;
    private boolean fighting;

    private FinishRouteGoal(Mob mob, BlockPos finish, FactionCombat combat, List<BlockPos> points, int step) {
        this.mob = mob;
        this.finish = finish;
        this.combat = combat;
        this.points = points;
        this.step = step;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    static void assign(Mob mob, BlockPos finish, List<BlockPos> points) {
        mob.addTag(ROUTE_TAG + finish.asLong());
        if (!points.isEmpty()) {
            mob.addTag(POINTS_TAG + points.stream().map(point -> Long.toString(point.asLong()))
                .collect(Collectors.joining(",")));
            mob.addTag(STEP_TAG + Math.min(1, points.size() - 1));
        }
        applyWaveSpeed(mob);
    }

    public static boolean hasRoute(Mob mob) {
        return mob.entityTags().stream().anyMatch(tag -> tag.startsWith(ROUTE_TAG));
    }

    static boolean hasWaypoints(Mob mob) {
        return mob.entityTags().stream().anyMatch(tag -> tag.startsWith(POINTS_TAG));
    }

    public static void restore(Mob mob, FactionCombat combat) {
        for (String tag : mob.entityTags()) {
            if (tag.startsWith(ROUTE_TAG)) {
                try {
                    BlockPos finish = BlockPos.of(Long.parseLong(tag.substring(ROUTE_TAG.length())));
                    applyWaveSpeed(mob);
                    List<BlockPos> points = restorePoints(mob, finish);
                    int step = points.isEmpty() ? 0 : restoreStep(mob, points.size());
                    FinishRouteGoal goal = new FinishRouteGoal(mob, finish, combat, points, step);
                    goal.updateCombatDirection();
                    mob.getGoalSelector().addGoal(-1, goal);
                    return;
                } catch (NumberFormatException ignored) {
                    // Ignore malformed route tags.
                }
            }
        }
    }

    private static List<BlockPos> restorePoints(Mob mob, BlockPos finish) {
        String tag = mob.entityTags().stream().filter(value -> value.startsWith(POINTS_TAG)).findFirst().orElse(null);
        if (tag == null) {
            return List.of();
        }
        String[] coordinates = tag.substring(POINTS_TAG.length()).split(",", -1);
        if (coordinates.length <= WaveRoute.MAX_PATH_POINTS) {
            try {
                List<BlockPos> points = new ArrayList<>();
                for (String coordinate : coordinates) {
                    points.add(BlockPos.of(Long.parseLong(coordinate)));
                }
                if (points.getLast().equals(finish)) {
                    return List.copyOf(points);
                }
            } catch (NumberFormatException ignored) {
                // Old or malformed paths can still use the saved finish.
            }
        }
        mob.removeTag(tag);
        return List.of();
    }

    private static int restoreStep(Mob mob, int count) {
        for (String tag : mob.entityTags()) {
            if (tag.startsWith(STEP_TAG)) {
                try {
                    return Math.clamp(Integer.parseInt(tag.substring(STEP_TAG.length())), 0, count - 1);
                } catch (NumberFormatException ignored) {
                    break;
                }
            }
        }
        return 0;
    }

    private static void applyWaveSpeed(Mob mob) {
        halveSpeed(mob.getAttribute(Attributes.MOVEMENT_SPEED));
        halveSpeed(mob.getAttribute(Attributes.FLYING_SPEED));
    }

    private static void halveSpeed(AttributeInstance speed) {
        if (speed != null && !speed.hasModifier(WAVE_SPEED.id())) {
            speed.addPermanentModifier(WAVE_SPEED);
        }
    }

    @Override
    public boolean canUse() {
        return true;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        mob.getNavigation().stop();
    }

    @Override
    public void tick() {
        var navigation = mob.getNavigation();
        boolean deviated = !points.isEmpty()
            && WaveRoute.deviated(mob.position(), points.get(Math.max(0, step - 1)), points.get(step));
        if (deviated) {
            abandonWaypoints();
        }
        if (combat != null && combat.hasLiveTarget()) {
            fighting = true;
            return;
        }
        if (fighting || deviated) {
            navigation.stop();
            retryTicks = 0;
            fighting = false;
        }
        if (!points.isEmpty()) {
            followWaypoints();
            return;
        }
        if (mob.onGround() && mob.getOnPosLegacy().equals(finish.below())) {
            SpawnZones.reachFinish(mob);
            return;
        }
        if (retryTicks-- <= 0 && navigation.isDone()) {
            retryTicks = 20;
            navigation.moveTo(navigation.createPath(finish, 0, pathRange(mob, finish)), 1.0);
        }
    }

    private void followWaypoints() {
        Vec3 next = WaveRoute.center(points.get(step));
        Vec3 position = mob.position();
        boolean reached = position.distanceToSqr(next) <= 0.25 * 0.25;
        if (reached || (step > 0 && WaveRoute.passed(position, points.get(step - 1), points.get(step)))) {
            if (step == points.size() - 1) {
                SpawnZones.reachFinish(mob);
                return;
            }
            mob.removeTag(STEP_TAG + step);
            mob.addTag(STEP_TAG + ++step);
            updateCombatDirection();
            next = WaveRoute.center(points.get(step));
        }
        mob.getMoveControl().setWantedPosition(next.x, next.y, next.z, 1.0);
    }

    private void abandonWaypoints() {
        points = List.of();
        mob.entityTags().stream().filter(tag -> tag.startsWith(POINTS_TAG) || tag.startsWith(STEP_TAG))
            .toList().forEach(mob::removeTag);
        updateCombatDirection();
    }

    private void updateCombatDirection() {
        if (combat != null) {
            combat.routeTo(points.isEmpty() ? Vec3.atBottomCenterOf(finish) : WaveRoute.center(points.get(step)));
        }
    }

    static int pathRange(Mob mob, BlockPos finish) {
        double distance = Math.sqrt(mob.blockPosition().distSqr(finish));
        return (int) Math.min(128, Math.ceil(distance) + 16);
    }
}
