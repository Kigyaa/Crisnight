package ru.layoutcamera;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Mob;

public final class AttackKnockback {
    static final double MAX_STRENGTH = 5;
    private static final String TAG = "layoutcamera_attack_knockback=";

    private AttackKnockback() {}

    static void assign(Mob mob, double strength) {
        mob.addTag(TAG + strength);
    }

    public static Double configuredStrength(DamageSource source) {
        if (source == null || !(source.getEntity() instanceof Mob mob)
            || (source.getDirectEntity() != mob && !ProjectileAttack.isShot(source.getDirectEntity()))) {
            return null;
        }
        for (String tag : mob.entityTags()) {
            if (tag.startsWith(TAG)) {
                try {
                    double value = Double.parseDouble(tag.substring(TAG.length()));
                    if (valid(value)) {
                        return value;
                    }
                } catch (NumberFormatException ignored) {
                    // Fall back to the default if the saved value is malformed.
                }
            }
        }
        return Pets.isConfigured(mob) ? 0.0 : null;
    }

    static boolean valid(double strength) {
        return Double.isFinite(strength) && strength >= 0 && strength <= MAX_STRENGTH;
    }
}
