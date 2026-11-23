package ru.layoutcamera.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.layoutcamera.FinishRouteGoal;
import ru.layoutcamera.FactionCombat;
import ru.layoutcamera.ProjectileAttack;

@Mixin(Mob.class)
abstract class MobMixin {
    @Unique
    private boolean layoutcamera$routeRestored;
    @Unique
    private boolean layoutcamera$factionLoaded;
    @Unique
    private FactionCombat layoutcamera$combat;

    @Inject(method = "aiStep", at = @At("TAIL"))
    private void layoutcamera$tickFactionCombat(CallbackInfo callback) {
        Mob mob = (Mob) (Object) this;
        if (!(mob.level() instanceof ServerLevel level)) {
            return;
        }
        FactionCombat combat = layoutcamera$getCombat(mob);
        if (combat != null) {
            combat.tick(level);
        }
    }

    @Inject(method = "canAttack", at = @At("HEAD"), cancellable = true)
    private void layoutcamera$filterTargetsByZone(LivingEntity target, CallbackInfoReturnable<Boolean> callback) {
        Mob attacker = (Mob) (Object) this;
        if (ProjectileAttack.usesConfiguredAttack(attacker)
            || (target instanceof Mob defender && (FactionCombat.usesAttackGrid(attacker)
                || !FactionCombat.permitsAttack(attacker, defender)))) {
            callback.setReturnValue(false);
        }
    }

    @Inject(method = "getTarget", at = @At("RETURN"), cancellable = true)
    private void layoutcamera$dropElevatedTarget(CallbackInfoReturnable<LivingEntity> callback) {
        Mob attacker = (Mob) (Object) this;
        if (callback.getReturnValue() != null && (ProjectileAttack.usesConfiguredAttack(attacker)
            || (callback.getReturnValue() instanceof Mob defender && !FactionCombat.canAttackAtHeight(attacker, defender)))) {
            callback.setReturnValue(null);
        }
    }

    @Inject(method = "doHurtTarget", at = @At("HEAD"), cancellable = true)
    private void layoutcamera$filterMeleeByZone(ServerLevel level, Entity target, CallbackInfoReturnable<Boolean> callback) {
        Mob attacker = (Mob) (Object) this;
        if (ProjectileAttack.usesConfiguredAttack(attacker)
            || (target instanceof Mob defender && (FactionCombat.usesAttackGrid(attacker)
                || !FactionCombat.permitsAttack(attacker, defender)))) {
            callback.setReturnValue(false);
        }
    }

    @Inject(method = "checkDespawn", at = @At("HEAD"), cancellable = true)
    private void layoutcamera$keepRoutedMobInPeaceful(CallbackInfo callback) {
        Mob mob = (Mob) (Object) this;
        if (mob.level().getDifficulty() == Difficulty.PEACEFUL && !mob.getType().isAllowedInPeaceful()
            && (FinishRouteGoal.hasRoute(mob) || FactionCombat.isPlanned(mob))) {
            callback.cancel();
        }
    }

    @Inject(method = "serverAiStep", at = @At("HEAD"))
    private void layoutcamera$restoreFinishRoute(CallbackInfo callback) {
        if (!layoutcamera$routeRestored) {
            layoutcamera$routeRestored = true;
            Mob mob = (Mob) (Object) this;
            FinishRouteGoal.restore(mob, layoutcamera$getCombat(mob));
        }
    }

    @Unique
    private FactionCombat layoutcamera$getCombat(Mob mob) {
        if (!layoutcamera$factionLoaded) {
            layoutcamera$factionLoaded = true;
            layoutcamera$combat = FactionCombat.restore(mob);
        }
        return layoutcamera$combat;
    }
}
