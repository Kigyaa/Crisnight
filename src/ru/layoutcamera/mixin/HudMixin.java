package ru.layoutcamera.mixin;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.joml.Matrix3x2fStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.layoutcamera.LayoutCamera;
import ru.layoutcamera.PlanningDrag;
import ru.layoutcamera.PlanningMobs;

@Mixin(Hud.class)
abstract class HudMixin {
    @Shadow public abstract boolean isHidden();

    @Inject(method = "extractItemHotbar", at = @At("HEAD"))
    private void layoutcamera$positionHotbar(GuiGraphicsExtractor graphics, DeltaTracker delta, CallbackInfo callback) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!LayoutCamera.isActive(minecraft)) {
            return;
        }

        int width = graphics.guiWidth();
        int height = graphics.guiHeight();
        float scale = LayoutCamera.hotbarScale(width, height);
        Matrix3x2fStack pose = graphics.pose();
        pose.pushMatrix();
        pose.translate(LayoutCamera.hotbarRight(minecraft, width, scale) - (width / 2 + 91) * scale,
            height - 4 - (height + 1) * scale);
        pose.scale(scale, scale);
    }

    @Inject(method = "extractItemHotbar", at = @At("RETURN"))
    private void layoutcamera$restoreHudPose(GuiGraphicsExtractor graphics, DeltaTracker delta, CallbackInfo callback) {
        if (LayoutCamera.isActive(Minecraft.getInstance())) {
            graphics.pose().popMatrix();
        }
    }

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void layoutcamera$drawHint(GuiGraphicsExtractor graphics, DeltaTracker delta, CallbackInfo callback) {
        Minecraft minecraft = Minecraft.getInstance();
        if (isHidden() || minecraft.player == null
            || minecraft.gui.screen() != null || minecraft.gui.overlay() != null) {
            return;
        }

        Font font = minecraft.font;
        int right = graphics.guiWidth() - 8;
        int bottom = LayoutCamera.hintBottom(minecraft, graphics.guiWidth(), graphics.guiHeight());
        int top = bottom - 19;
        int left = LayoutCamera.hintLeft(minecraft, right);
        int key = left + 3;
        int keyTop = top + 3;
        boolean active = LayoutCamera.isActive(minecraft);

        graphics.nextStratum();
        graphics.fill(left, top, right, bottom,
            active && LayoutCamera.isOverHint(minecraft) ? 0xee36323a : 0xd91a1c20);
        graphics.fill(key, keyTop, key + 15, keyTop + 13, active ? 0xffe3ae58 : 0xffbabfc7);
        graphics.fill(key + 1, keyTop + 1, key + 14, keyTop + 12, active ? 0xff554222 : 0xff383e48);
        graphics.centeredText(font, "J", key + 7, keyTop + 2, 0xffffffff);
        graphics.text(font, LayoutCamera.LABEL, key + 21, keyTop + 2, 0xfff5f6f7);
        PlanningMobs.drawDeleteButton(minecraft, graphics);
        PlanningDrag.drawCursorPreview(minecraft, graphics);
    }

    @Inject(method = "extractCrosshair", at = @At("HEAD"), cancellable = true)
    private void layoutcamera$hideCrosshair(GuiGraphicsExtractor graphics, DeltaTracker delta, CallbackInfo callback) {
        if (LayoutCamera.isActive(Minecraft.getInstance())) {
            callback.cancel();
        }
    }
}
