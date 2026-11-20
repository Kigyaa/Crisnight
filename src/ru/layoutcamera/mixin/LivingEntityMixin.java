package ru.layoutcamera.mixin;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.layoutcamera.FinishRouteGoal;
import ru.layoutcamera.PlanningMobs;

@Mixin(LivingEntity.class)
abstract class LivingEntityMixin {
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
