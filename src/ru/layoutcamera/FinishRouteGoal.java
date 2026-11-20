package ru.layoutcamera;

import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;

public final class FinishRouteGoal extends Goal {
    private static final String ROUTE_TAG = "layoutcamera_finish=";
    private final Mob mob;
    private final BlockPos finish;
    private final FactionCombat combat;
    private int retryTicks;
    private boolean fighting;

    private FinishRouteGoal(Mob mob, BlockPos finish, FactionCombat combat) {
        this.mob = mob;
        this.finish = finish;
        this.combat = combat;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    static void assign(Mob mob, BlockPos finish) {
        mob.addTag(ROUTE_TAG + finish.asLong());
    }

    public static boolean hasRoute(Mob mob) {
        return mob.entityTags().stream().anyMatch(tag -> tag.startsWith(ROUTE_TAG));
    }

    public static void restore(Mob mob, FactionCombat combat) {
        for (String tag : mob.entityTags()) {
            if (tag.startsWith(ROUTE_TAG)) {
                try {
                    BlockPos finish = BlockPos.of(Long.parseLong(tag.substring(ROUTE_TAG.length())));
                    if (combat != null) {
                        combat.routeTo(finish);
                    }
                    mob.getGoalSelector().addGoal(-1, new FinishRouteGoal(mob, finish, combat));
                    return;
                } catch (NumberFormatException ignored) {
                    // Ignore malformed route tags.
                }
            }
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
    public void tick() {
        var navigation = mob.getNavigation();
        if (combat != null && combat.hasLiveTarget()) {
            fighting = true;
            return;
        }
        if (fighting) {
            navigation.stop();
            retryTicks = 0;
            fighting = false;
        }
        if (mob.onGround() && mob.getOnPosLegacy().equals(finish.below())) {
            mob.discard();
            return;
        }
        if (retryTicks-- <= 0 && navigation.isDone()) {
            retryTicks = 20;
            double distance = Math.sqrt(mob.blockPosition().distSqr(finish));
            int pathRange = (int) Math.min(128, Math.ceil(distance) + 16);
            navigation.moveTo(navigation.createPath(finish, 0, pathRange), 1.0);
        }
    }
}
