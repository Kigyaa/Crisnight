package ru.layoutcamera;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

public final class ProjectileAttack {
    private static final String ATTACK_MODE = "layoutcamera_configured_attack";
    private static final String PROJECTILE = "layoutcamera_projectile=";
    private static final String DAMAGE = "layoutcamera_projectile_damage=";
    private static final float SPEED = 1.6f;

    private ProjectileAttack() {}

    enum Ammo {
        ARROW(EntityTypes.ARROW, 0.05),
        SNOWBALL(EntityTypes.SNOWBALL, 0.03),
        EGG(EntityTypes.EGG, 0.03);

        private final EntityType<? extends Projectile> type;
        private final double gravity;

        Ammo(EntityType<? extends Projectile> type, double gravity) {
            this.type = type;
            this.gravity = gravity;
        }
    }

    static void assign(Mob mob, PetConfig config) {
        String type = config.text("attack_type", "");
        if ("melee".equals(type) || "ranged".equals(type)) {
            mob.addTag(ATTACK_MODE);
        }
        Ammo ammo = config.projectile();
        if (ammo != null) {
            mob.addTag(PROJECTILE + ammo.name());
        }
    }

    static Ammo restore(Mob mob) {
        for (String tag : mob.entityTags()) {
            if (tag.startsWith(PROJECTILE)) {
                try {
                    return Ammo.valueOf(tag.substring(PROJECTILE.length()));
                } catch (IllegalArgumentException ignored) {
                    // Invalid entity tags fall back to the default attack.
                }
            }
        }
        return null;
    }

    public static boolean usesConfiguredAttack(Mob mob) {
        return mob.entityTags().contains(ATTACK_MODE);
    }

    static void shoot(ServerLevel level, Mob attacker, Mob target, Ammo ammo, float damage) {
        Projectile shot = ammo.type.create(level, EntitySpawnReason.TRIGGERED);
        if (shot == null) {
            return;
        }
        shot.setOwner(attacker);
        shot.addTag(DAMAGE + damage);
        shot.setPos(attacker.getX(), attacker.getEyeY() - 0.1, attacker.getZ());
        double x = target.getX() - shot.getX();
        double y = target.getY(0.5) - shot.getY();
        double z = target.getZ() - shot.getZ();
        double flightTicks = Math.sqrt(x * x + y * y + z * z) / SPEED;
        double rise = ammo.gravity * flightTicks * Math.max(0, flightTicks - 1) / 2;
        Projectile.spawnProjectileUsingShoot(shot, level, ItemStack.EMPTY, x, y + rise, z, SPEED, 0);
    }

    public static boolean isShot(Entity entity) {
        return entity instanceof Projectile && entity.entityTags().stream().anyMatch(tag -> tag.startsWith(DAMAGE));
    }

    public static boolean canHit(Projectile shot, Entity candidate) {
        return shot.getOwner() instanceof Mob attacker && candidate instanceof Mob target
            && FactionCombat.canHitProjectileTarget(attacker, target);
    }

    public static void hit(ServerLevel level, Projectile shot, HitResult hit) {
        if (hit instanceof EntityHitResult entityHit && canHit(shot, entityHit.getEntity())) {
            Mob target = (Mob) entityHit.getEntity();
            Entity attacker = shot.getOwner();
            var sources = level.damageSources();
            var source = shot instanceof AbstractArrow arrow ? sources.arrow(arrow, attacker) : sources.thrown(shot, attacker);
            target.hurtServer(level, source, readDamage(shot));
        }
        if (!(shot instanceof AbstractArrow)) {
            level.broadcastEntityEvent(shot, (byte) 3);
        }
        shot.discard();
    }

    private static float readDamage(Projectile shot) {
        for (String tag : shot.entityTags()) {
            if (tag.startsWith(DAMAGE)) {
                try {
                    float damage = Float.parseFloat(tag.substring(DAMAGE.length()));
                    return Float.isFinite(damage) && damage >= 0 ? damage : 0;
                } catch (NumberFormatException ignored) {
                    return 0;
                }
            }
        }
        return 0;
    }
}
