package ru.layoutcamera.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.layoutcamera.AttackKnockback;
import ru.layoutcamera.FactionCombat;
import ru.layoutcamera.FinishRouteGoal;
import ru.layoutcamera.PlanningMobs;

@Mixin(LivingEntity.class)
abstract class LivingEntityMixin {
    @Inject(method = "hurtServer", at = @At("HEAD"), cancellable = true)
    private void layoutcamera$protectElevatedMob(ServerLevel level, DamageSource source, float amount,
                                                  CallbackInfoReturnable<Boolean> callback) {
        if ((Object) this instanceof Mob defender && source.getEntity() instanceof Mob attacker
            && !FactionCombat.canAttackAtHeight(attacker, defender)) {
            callback.setReturnValue(false);
        }
    }

    @ModifyArg(method = "dealDefaultKnockback", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/LivingEntity;knockback(DDDLnet/minecraft/world/damagesource/DamageSource;F)V"),
        index = 0)
    private double layoutcamera$applyAttackKnockback(double vanillaStrength, double x, double z, DamageSource source,
                                                     float damage) {
        Double configured = AttackKnockback.configuredStrength(source);
        return configured == null ? vanillaStrength : configured;
    }

    @Inject(method = "causeExtraKnockback", at = @At("HEAD"), cancellable = true)
    private void layoutcamera$skipVanillaKnockbackBonus(Entity target, float strength, Vec3 movement, DamageSource source,
                                                       float damage, boolean melee, CallbackInfo callback) {
        if (target instanceof LivingEntity && AttackKnockback.configuredStrength(source) != null) {
            callback.cancel();
        }
    }

    @Inject(method = "maxUpStep", at = @At("HEAD"), cancellable = true)
    private void layoutcamera$preventSpawnMobSteppingUp(CallbackInfoReturnable<Float> callback) {
        if ((Object) this instanceof Mob mob && FinishRouteGoal.hasRoute(mob)) {
            callback.setReturnValue(0.0f);
        }
    }

    @Inject(method = "jumpFromGround", at = @At("HEAD"), cancellable = true)
    private void layoutcamera$preventSpawnMobJumping(CallbackInfo callback) {
        if ((Object) this instanceof Mob mob && FinishRouteGoal.hasRoute(mob)) {
            callback.cancel();
        }
    }

    @Inject(method = "onClimbable", at = @At("HEAD"), cancellable = true)
    private void layoutcamera$preventSpawnMobClimbing(CallbackInfoReturnable<Boolean> callback) {
        if ((Object) this instanceof Mob mob && FinishRouteGoal.hasRoute(mob)) {
            callback.setReturnValue(false);
        }
    }

    @Inject(method = {"isPickable", "isPushable"}, at = @At("HEAD"), cancellable = true)
    private void layoutcamera$ignorePlanningMobInteraction(CallbackInfoReturnable<Boolean> callback) {
        if (PlanningMobs.isPreview((LivingEntity) (Object) this)) {
            callback.setReturnValue(false);
        }
    }
}
