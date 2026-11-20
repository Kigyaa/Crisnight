package ru.layoutcamera.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.layoutcamera.LayoutCamera;
import ru.layoutcamera.PlanningDrag;
import ru.layoutcamera.PlanningMobs;
import ru.layoutcamera.ZoneSelection;

@Mixin(MouseHandler.class)
abstract class MouseMixin {
    @Inject(method = "grabMouse", at = @At("HEAD"), cancellable = true)
    private void layoutcamera$keepCursorFree(CallbackInfo callback) {
        if (LayoutCamera.isActive(Minecraft.getInstance())) {
            callback.cancel();
        }
    }

    @Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
    private void layoutcamera$handleCursor(long window, MouseButtonInfo button, int action, CallbackInfo callback) {
        Minecraft minecraft = Minecraft.getInstance();
        if (window != minecraft.getWindow().handle()) {
            return;
        }
        if (button.button() == InputConstants.MOUSE_BUTTON_LEFT
            && action == InputConstants.RELEASE && PlanningDrag.active()) {
            if (minecraft.gui.screen() == null && minecraft.gui.overlay() == null) {
                PlanningDrag.release(minecraft);
            } else {
                PlanningDrag.cancel();
            }
            callback.cancel();
            return;
        }
        if (minecraft.gui.screen() != null || minecraft.gui.overlay() != null) {
            return;
        }
        if (action != InputConstants.PRESS) {
            return;
        }
        boolean layout = LayoutCamera.isActive(minecraft);
        if (layout && !minecraft.gui.hud.isHidden()) {
            if (button.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
                PlanningMobs.selectAtCursor(minecraft);
                callback.cancel();
                return;
            }
            if (button.button() == InputConstants.MOUSE_BUTTON_LEFT) {
                if (PlanningMobs.deleteAtCursor(minecraft)) {
                    callback.cancel();
                    return;
                }
                PlanningMobs.hideDeleteButton();
            }
        }
        int slot = layout && !minecraft.gui.hud.isHidden()
            && button.button() == InputConstants.MOUSE_BUTTON_LEFT
            ? LayoutCamera.hotbarSlotAtCursor(minecraft) : -1;
        if (slot >= 0) {
            minecraft.player.getInventory().setSelectedSlot(slot);
            PlanningDrag.begin(minecraft, slot);
        } else if (layout && button.button() == InputConstants.MOUSE_BUTTON_LEFT && !minecraft.gui.hud.isHidden()
            && LayoutCamera.isOverHint(minecraft)) {
            LayoutCamera.toggle(minecraft);
        } else if (!layout && button.button() == InputConstants.MOUSE_BUTTON_LEFT
            && minecraft.mouseHandler.isMouseGrabbed() && ZoneSelection.isHoldingTool(minecraft)) {
            ZoneSelection.selectCorner(minecraft);
        } else if (!layout) {
            return;
        }
        callback.cancel();
    }
}
