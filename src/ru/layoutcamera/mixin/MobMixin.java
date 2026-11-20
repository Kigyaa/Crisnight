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
        if (target instanceof Mob defender && !FactionCombat.permitsAttack((Mob) (Object) this, defender)) {
            callback.setReturnValue(false);
        }
    }

    @Inject(method = "doHurtTarget", at = @At("HEAD"), cancellable = true)
    private void layoutcamera$filterMeleeByZone(ServerLevel level, Entity target, CallbackInfoReturnable<Boolean> callback) {
        if (target instanceof Mob defender && !FactionCombat.permitsAttack((Mob) (Object) this, defender)) {
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
