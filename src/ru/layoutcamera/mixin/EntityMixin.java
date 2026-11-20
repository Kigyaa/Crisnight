package ru.layoutcamera.mixin;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.layoutcamera.FactionCombat;
import ru.layoutcamera.StageCopies;

@Mixin(Entity.class)
abstract class EntityMixin {
    @Inject(method = "shouldBeSaved", at = @At("HEAD"), cancellable = true)
    private void layoutcamera$skipSavingTemporaryDefender(CallbackInfoReturnable<Boolean> callback) {
        Entity entity = (Entity) (Object) this;
        if (FactionCombat.isPlanned(entity) || StageCopies.isTemporaryMob(entity)) {
            callback.setReturnValue(false);
        }
    }
}
