package ru.layoutcamera.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.layoutcamera.LayoutCamera;
import ru.layoutcamera.ZoneSelection;

@Mixin(KeyboardHandler.class)
abstract class KeyboardMixin {
    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void layoutcamera$onKey(long window, int action, KeyEvent event, CallbackInfo callback) {
        Minecraft minecraft = Minecraft.getInstance();
        if (action == InputConstants.PRESS && window == minecraft.getWindow().handle()
            && (event.key() == InputConstants.KEY_LSHIFT || event.key() == InputConstants.KEY_RSHIFT)
            && minecraft.gui.screen() == null && minecraft.gui.overlay() == null
            && !LayoutCamera.isActive(minecraft)
            && ZoneSelection.isHoldingTool(minecraft)) {
            ZoneSelection.switchZone(minecraft);
        }
        if (!layoutcamera$isPlanningKey(action, event)) {
            return;
        }

        if (window == minecraft.getWindow().handle()
            && minecraft.player != null
            && minecraft.getCameraEntity() == minecraft.player
            && !minecraft.player.isSleeping()
            && minecraft.gui.screen() == null
            && minecraft.gui.overlay() == null) {
            LayoutCamera.toggle(minecraft);
            callback.cancel();
        }
    }

    private static boolean layoutcamera$isPlanningKey(int action, KeyEvent event) {
        int shortcutModifiers = InputConstants.MOD_SHIFT | InputConstants.MOD_CONTROL
            | InputConstants.MOD_ALT | InputConstants.MOD_SUPER;
        return action == InputConstants.PRESS && event.key() == InputConstants.KEY_J
            && (event.modifiers() & shortcutModifiers) == 0;
    }
}
