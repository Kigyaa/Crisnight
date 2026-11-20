package ru.layoutcamera;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

public final class FactionCombat {
    private static final String PEACEFUL_TAG = "layoutcamera_faction_peaceful";
    private static final String HOSTILE_TAG = "layoutcamera_faction_hostile";
    private static final String DAMAGE_TAG = "layoutcamera_faction_damage=";
    private static final String SPEED_TAG = "layoutcamera_faction_speed=";
    private static final String PLANNED_TAG = "layoutcamera_wave_defender";
    private static final double SEARCH_RANGE = 12;

    private final Mob mob;
    private final Side side;
    private final float damage;
    private final int attackInterval;
    private Mob target;
    private BlockPos finish;
    private int cooldown;

    enum Side { PEACEFUL, HOSTILE }

    private FactionCombat(Mob mob, Side side) {
        this.mob = mob;
        this.side = side;
        var attack = mob.getAttribute(Attributes.ATTACK_DAMAGE);
        damage = (float) Math.min(Float.MAX_VALUE,
            readPositive(mob, DAMAGE_TAG, attack == null || attack.getValue() <= 0 ? 2 : attack.getValue()));
        attackInterval = Math.max(1, (int) Math.ceil(20 / readPositive(mob, SPEED_TAG, 1)));
    }

    static void assign(Mob mob, PetConfig config) {
        Side side = config == null ? null : config.faction();
        if (side == null) {
            side = mob.getType().getCategory() == MobCategory.MONSTER ? Side.HOSTILE : Side.PEACEFUL;
        }
        mob.addTag(side == Side.HOSTILE ? HOSTILE_TAG : PEACEFUL_TAG);
        if (config != null) {
            AttackBlocks.assign(mob, config.attackBlock());
            addPositive(mob, DAMAGE_TAG, config.number("damage", 0));
            addPositive(mob, SPEED_TAG, config.number("attack_speed", 0));
        }
    }

    public static FactionCombat restore(Mob mob) {
        Side side = sideOf(mob);
        return side == null ? null : new FactionCombat(mob, side);
    }

    static void markPlanned(Mob mob) {
        mob.addTag(PLANNED_TAG);
    }

    public static boolean isPlanned(net.minecraft.world.entity.Entity entity) {
        return entity.entityTags().contains(PLANNED_TAG);
    }

    public void tick(ServerLevel level) {
        if (!mob.isAlive()) {
            return;
        }
        if (cooldown > 0) {
            cooldown--;
        }
        if (target != null && !hasLiveTarget()) {
            target = null;
        }
        boolean scan = mob.tickCount % 10 == 0;
        if (scan && (finish == null || target == null)) {
            target = findTarget(level);
            if (target != null && finish != null) {
                if (AttackBlocks.reserve(target, mob)) {
                    mob.getNavigation().stop();
                } else {
                    target = null;
                }
            }
        }
        boolean canHit = target != null && mob.isWithinMeleeAttackRange(target) && mob.hasLineOfSight(target);
        if (target != null && !mob.isNoAi()) {
            if (finish != null && canHit) {
                mob.getNavigation().stop();
            } else if (scan && !canHit) {
                mob.getNavigation().moveTo(target, 1.05);
            }
        }
        if (canHit && hasLiveTarget() && cooldown == 0 && permitsAttack(mob, target)) {
            target.hurtServer(level, mob.damageSources().mobAttack(mob), damage);
            cooldown = attackInterval;
        }
    }

    void routeTo(BlockPos finish) {
        this.finish = finish;
    }

    boolean hasLiveTarget() {
        return target != null && target.isAlive() && target.level() == mob.level();
    }

    private Mob findTarget(ServerLevel level) {
        Mob nearest = null;
        double nearestDistance = SEARCH_RANGE * SEARCH_RANGE;
        for (Mob candidate : level.getEntitiesOfClass(Mob.class, mob.getBoundingBox().inflate(SEARCH_RANGE),
            entity -> entity.isAlive() && sideOf(entity) == (side == Side.HOSTILE ? Side.PEACEFUL : Side.HOSTILE)
                && AttackZones.allows(mob, entity)
                && (finish != null ? AttackBlocks.hasSpace(entity)
                    : !FinishRouteGoal.hasRoute(entity) || AttackBlocks.holds(mob, entity)))) {
            double distance = mob.distanceToSqr(candidate);
            if (distance <= nearestDistance && isAhead(candidate) && mob.hasLineOfSight(candidate)) {
                nearest = candidate;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    public static boolean permitsAttack(Mob attacker, Mob defender) {
        if (!AttackZones.allows(attacker, defender)) {
            return false;
        }
        if (sideOf(attacker) == null || sideOf(defender) == null) {
            return true;
        }
        if (FinishRouteGoal.hasRoute(attacker)) {
            return AttackBlocks.holds(defender, attacker);
        }
        return !FinishRouteGoal.hasRoute(defender) || AttackBlocks.holds(attacker, defender);
    }

    private boolean isAhead(Mob candidate) {
        if (finish == null) {
            return true;
        }
        Vec3 direction = Vec3.atBottomCenterOf(finish).subtract(mob.position());
        Vec3 offset = candidate.position().subtract(mob.position());
        return direction.x * offset.x + direction.z * offset.z >= 0;
    }

    private static Side sideOf(Mob mob) {
        var tags = mob.entityTags();
        if (tags.contains(HOSTILE_TAG)) {
            return Side.HOSTILE;
        }
        return tags.contains(PEACEFUL_TAG) ? Side.PEACEFUL : null;
    }

    private static void addPositive(Mob mob, String key, Double value) {
        if (value != null && value > 0) {
            mob.addTag(key + value);
        }
    }

    private static double readPositive(Mob mob, String key, double fallback) {
        for (String tag : mob.entityTags()) {
            if (tag.startsWith(key)) {
                try {
                    double value = Double.parseDouble(tag.substring(key.length()));
                    if (value > 0 && Double.isFinite(value)) {
                        return value;
                    }
                } catch (NumberFormatException ignored) {
                    // Ignore invalid values in saved entity tags.
                }
            }
        }
        return fallback;
    }
}
