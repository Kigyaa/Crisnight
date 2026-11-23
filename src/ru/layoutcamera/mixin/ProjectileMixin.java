package ru.layoutcamera.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileDeflection;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.layoutcamera.ProjectileAttack;

@Mixin(Projectile.class)
abstract class ProjectileMixin {
    @Inject(method = "canHitEntity", at = @At("HEAD"), cancellable = true)
    private void layoutcamera$filterProjectileTargets(Entity target, CallbackInfoReturnable<Boolean> callback) {
        Projectile shot = (Projectile) (Object) this;
        if (ProjectileAttack.isShot(shot) && !ProjectileAttack.canHit(shot, target)) {
            callback.setReturnValue(false);
        }
    }

    @Inject(method = "hitTargetOrDeflectSelf", at = @At("HEAD"), cancellable = true)
    private void layoutcamera$applyConfiguredDamage(HitResult hit,
                                                    CallbackInfoReturnable<ProjectileDeflection> callback) {
        Projectile shot = (Projectile) (Object) this;
        if (shot.level() instanceof ServerLevel level && ProjectileAttack.isShot(shot)) {
            ProjectileAttack.hit(level, shot, hit);
            callback.setReturnValue(ProjectileDeflection.NONE);
        }
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void layoutcamera$discardExpiredShot(CallbackInfo callback) {
        Projectile shot = (Projectile) (Object) this;
        if (ProjectileAttack.isShot(shot)
            && (shot.tickCount >= 100 || shot.getOwner() == null || !shot.getOwner().isAlive())) {
            shot.discard();
        }
    }
}
