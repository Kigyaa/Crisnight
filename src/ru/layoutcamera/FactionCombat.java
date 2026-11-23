package ru.layoutcamera;

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
    private final AttackGrid attackGrid;
    private final ProjectileAttack.Ammo ammo;
    private Mob target;
    private Vec3 waypoint;
    private int cooldown;

    enum Side { PEACEFUL, HOSTILE }

    private FactionCombat(Mob mob, Side side) {
        this.mob = mob;
        this.side = side;
        var attack = mob.getAttribute(Attributes.ATTACK_DAMAGE);
        damage = (float) Math.min(Float.MAX_VALUE,
            readPositive(mob, DAMAGE_TAG, attack == null || attack.getValue() <= 0 ? 2 : attack.getValue()));
        attackInterval = Math.max(1, (int) Math.ceil(20 / readPositive(mob, SPEED_TAG, 1)));
        attackGrid = AttackGrid.restore(mob);
        ammo = ProjectileAttack.restore(mob);
    }

    static void assign(Mob mob, PetConfig config) {
        Side side = config == null ? null : config.faction();
        if (side == null) {
            side = mob.getType().getCategory() == MobCategory.MONSTER ? Side.HOSTILE : Side.PEACEFUL;
        }
        mob.addTag(side == Side.HOSTILE ? HOSTILE_TAG : PEACEFUL_TAG);
        if (config != null) {
            AttackBlocks.assign(mob, config.attackBlock());
            AttackGrid.assign(mob, config.attackGrid());
            ProjectileAttack.assign(mob, config);
            AttackKnockback.assign(mob, config.attackKnockback());
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
        if (attackGrid != null && !FinishRouteGoal.hasRoute(mob)) {
            tickGrid(level);
            return;
        }
        if (target != null && !hasLiveTarget()) {
            if (waypoint != null) {
                AttackBlocks.release(target, mob);
            }
            target = null;
        }
        boolean scan = mob.tickCount % 10 == 0;
        if (scan && (waypoint == null || target == null)) {
            target = findTarget(level);
            if (target != null && waypoint != null) {
                if (AttackBlocks.reserve(target, mob)) {
                    mob.getNavigation().stop();
                } else {
                    target = null;
                }
            }
        }
        boolean canHit = target != null
            && (ammo == null ? mob.isWithinMeleeAttackRange(target) : withinRangedDistance(target))
            && mob.hasLineOfSight(target);
        if (target != null && !mob.isNoAi()) {
            if (waypoint != null && canHit) {
                mob.getNavigation().stop();
            } else if (scan && !canHit) {
                mob.getNavigation().moveTo(target, 1.05);
            }
        }
        if (canHit && hasLiveTarget() && cooldown == 0 && permitsAttack(mob, target)) {
            attack(level);
        }
    }

    private void tickGrid(ServerLevel level) {
        if (attackGrid.isEmpty()) {
            return;
        }
        var frame = new AttackGrid.Frame(mob.position(), mob.getYRot());
        if (target == null || !gridTarget(frame, target)) {
            target = null;
            double nearest = Double.POSITIVE_INFINITY;
            for (Mob candidate : level.getEntitiesOfClass(Mob.class, attackGrid.searchBounds(frame),
                mob -> gridTarget(frame, mob))) {
                double distance = mob.distanceToSqr(candidate);
                if (distance < nearest) {
                    nearest = distance;
                    target = candidate;
                }
            }
        }
        if (target != null && cooldown == 0) {
            attack(level);
        }
    }

    private void attack(ServerLevel level) {
        if (ammo == null) {
            target.hurtServer(level, mob.damageSources().mobAttack(mob), damage);
        } else {
            ProjectileAttack.shoot(level, mob, target, ammo, damage);
        }
        cooldown = attackInterval;
    }

    private boolean withinRangedDistance(Mob candidate) {
        return mob.distanceToSqr(candidate) <= SEARCH_RANGE * SEARCH_RANGE;
    }

    private boolean gridTarget(AttackGrid.Frame frame, Mob candidate) {
        return candidate.isAlive() && candidate.level() == mob.level() && FinishRouteGoal.hasRoute(candidate)
            && sideOf(candidate) == (side == Side.HOSTILE ? Side.PEACEFUL : Side.HOSTILE)
            && AttackZones.allows(mob, candidate) && attackGrid.contains(frame, candidate.position())
            && (ammo == null || withinRangedDistance(candidate))
            && mob.hasLineOfSight(candidate);
    }

    static boolean canHitProjectileTarget(Mob attacker, Mob target) {
        Side side = sideOf(attacker);
        return attacker.isAlive() && target.isAlive() && attacker.level() == target.level()
            && side != null && sideOf(target) == (side == Side.HOSTILE ? Side.PEACEFUL : Side.HOSTILE)
            && canAttackAtHeight(attacker, target) && AttackZones.allows(attacker, target)
            && (!usesAttackGrid(attacker) || FinishRouteGoal.hasRoute(target));
    }

    public static boolean usesAttackGrid(Mob mob) {
        return AttackGrid.isAssigned(mob) && !FinishRouteGoal.hasRoute(mob);
    }

    void routeTo(Vec3 waypoint) {
        this.waypoint = waypoint;
    }

    boolean hasLiveTarget() {
        return target != null && target.isAlive() && target.level() == mob.level() && canAttackAtHeight(mob, target);
    }

    private Mob findTarget(ServerLevel level) {
        Mob nearest = null;
        double nearestDistance = SEARCH_RANGE * SEARCH_RANGE;
        for (Mob candidate : level.getEntitiesOfClass(Mob.class, mob.getBoundingBox().inflate(SEARCH_RANGE),
            entity -> entity.isAlive() && sideOf(entity) == (side == Side.HOSTILE ? Side.PEACEFUL : Side.HOSTILE)
                && canAttackAtHeight(mob, entity) && AttackZones.allows(mob, entity)
                && (waypoint != null ? AttackBlocks.hasSpace(entity)
                    : !FinishRouteGoal.hasRoute(entity) || canHitPassingEnemy(mob, entity)))) {
            double distance = mob.distanceToSqr(candidate);
            if (distance <= nearestDistance && isAhead(candidate) && mob.hasLineOfSight(candidate)) {
                nearest = candidate;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    public static boolean permitsAttack(Mob attacker, Mob defender) {
        if (!canAttackAtHeight(attacker, defender) || !AttackZones.allows(attacker, defender)) {
            return false;
        }
        if (sideOf(attacker) == null || sideOf(defender) == null) {
            return true;
        }
        if (FinishRouteGoal.hasRoute(attacker)) {
            return AttackBlocks.holds(defender, attacker);
        }
        return !FinishRouteGoal.hasRoute(defender) || canHitPassingEnemy(attacker, defender);
    }

    private static boolean canHitPassingEnemy(Mob defender, Mob enemy) {
        return isPlanned(defender) || ProjectileAttack.usesConfiguredAttack(defender) || AttackBlocks.holds(defender, enemy);
    }

    public static boolean canAttackAtHeight(Mob attacker, Mob defender) {
        return !FinishRouteGoal.hasRoute(attacker) || defender.getY() - attacker.getY() < 1.0;
    }

    private boolean isAhead(Mob candidate) {
        if (waypoint == null) {
            return true;
        }
        Vec3 direction = waypoint.subtract(mob.position());
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
