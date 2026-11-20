package ru.layoutcamera.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.layoutcamera.LayoutCamera;

@Mixin(LocalPlayer.class)
abstract class LocalPlayerMixin {
    @Shadow private int autoJumpTime;

    @Inject(method = "aiStep", at = @At("HEAD"))
    private void layoutcamera$clearQueuedAutoJump(CallbackInfo callback) {
        if (LayoutCamera.isActive(Minecraft.getInstance())) {
            autoJumpTime = 0;
        }
    }
}
