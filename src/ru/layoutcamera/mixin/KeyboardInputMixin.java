package ru.layoutcamera.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.layoutcamera.LayoutCamera;

@Mixin(KeyboardInput.class)
abstract class KeyboardInputMixin extends ClientInput {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void layoutcamera$blockMovement(CallbackInfo callback) {
        if (LayoutCamera.isActive(Minecraft.getInstance())) {
            keyPresses = Input.EMPTY;
            moveVector = Vec2.ZERO;
            callback.cancel();
        }
    }
}
