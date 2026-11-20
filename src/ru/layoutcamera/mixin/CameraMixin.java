package ru.layoutcamera.mixin;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.layoutcamera.LayoutCamera;

@Mixin(Camera.class)
abstract class CameraMixin {
    @Shadow private Entity entity;
    @Shadow private boolean detached;

    @Shadow protected abstract void setRotation(float yaw, float pitch);
    @Shadow protected abstract void setPosition(Vec3 position);
    @Shadow public abstract boolean isPanoramicMode();

    @Inject(method = "alignWithEntity", at = @At("HEAD"), cancellable = true)
    private void layoutcamera$align(float partialTick, CallbackInfo callback) {
        Minecraft minecraft = Minecraft.getInstance();
        if (isPanoramicMode() || !LayoutCamera.isActive(minecraft) || entity != minecraft.player) {
            return;
        }

        detached = false;
        setPosition(entity.getEyePosition(partialTick));
        setRotation(LayoutCamera.yaw(), 60.0f);
        callback.cancel();
    }

    @Inject(method = "reset", at = @At("TAIL"))
    private void layoutcamera$reset(CallbackInfo callback) {
        LayoutCamera.reset();
    }
}
