package ru.layoutcamera.mixin;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.layoutcamera.LayoutCamera;

@Mixin(GameRenderer.class)
abstract class GameRendererMixin {
    @Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
    private void layoutcamera$hideHand(CallbackInfo callback) {
        if (LayoutCamera.isActive(Minecraft.getInstance())) {
            callback.cancel();
        }
    }

    @Inject(method = "extractOptions", at = @At("TAIL"))
    private void layoutcamera$applyRenderOptions(CallbackInfo callback) {
        if (LayoutCamera.isActive(Minecraft.getInstance())) {
            GameRenderState state = ((GameRenderer) (Object) this).gameRenderState();
            state.optionsRenderState.cameraType = CameraType.FIRST_PERSON;
            state.optionsRenderState.bobView = false;
        }
    }
}
