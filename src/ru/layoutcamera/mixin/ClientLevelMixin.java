package ru.layoutcamera.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.layoutcamera.PlanningMobs;

@Mixin(ClientLevel.class)
abstract class ClientLevelMixin {
    @Inject(method = "tickNonPassenger", at = @At("HEAD"), cancellable = true)
    private void layoutcamera$keepPlanningMobStill(Entity entity, CallbackInfo callback) {
        if (PlanningMobs.isPreview(entity)) {
            callback.cancel();
        }
    }
}
