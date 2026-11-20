package ru.layoutcamera;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.HumanoidArm;

public final class LayoutCamera {
    private static LocalPlayer activePlayer;
    private static float yaw;
    public static final String LABEL = "Изменить планировку";

    private LayoutCamera() {}

    public static void toggle(Minecraft minecraft) {
        if (isActive(minecraft)) {
            discardInventoryPresses(minecraft);
            reset();
            minecraft.mouseHandler.grabMouse();
            return;
        }
        activePlayer = minecraft.player;
        yaw = 90.0f * Math.round(activePlayer.getYRot() / 90.0f);
        minecraft.options.keyInventory.setDown(false);
        minecraft.mouseHandler.releaseMouse();
    }

    public static boolean isActive(Minecraft minecraft) {
        return activePlayer != null
            && activePlayer == minecraft.player
            && activePlayer == minecraft.getCameraEntity()
            && !activePlayer.isSleeping();
    }

    public static float yaw() {
        return yaw;
    }

    public static void reset() {
        activePlayer = null;
        PlanningDrag.cancel();
        PlanningMobs.hideDeleteButton();
    }

    public static boolean isOverHint(Minecraft minecraft) {
        Window window = minecraft.getWindow();
        int right = window.getGuiScaledWidth() - 8;
        int bottom = hintBottom(minecraft, window.getGuiScaledWidth(), window.getGuiScaledHeight());
        double x = minecraft.mouseHandler.getScaledXPos(window);
        double y = minecraft.mouseHandler.getScaledYPos(window);
        return x >= hintLeft(minecraft, right) && x < right && y >= bottom - 19 && y < bottom;
    }

    public static float hotbarScale(int width, int height) {
        // The nine slots occupy about five sixths of the reference image.
        return Math.min(width * 0.83f / 182.0f, height * 0.16f / 24.0f);
    }

    public static float hotbarRight(Minecraft minecraft, int width, float scale) {
        boolean offhandOnRight = !minecraft.player.getOffhandItem().isEmpty()
            && minecraft.player.getMainArm() == HumanoidArm.LEFT;
        return width - 4 - (offhandOnRight ? 29 : 1) * scale;
    }

    public static int hotbarSlotAtCursor(Minecraft minecraft) {
        Window window = minecraft.getWindow();
        int width = window.getGuiScaledWidth();
        int height = window.getGuiScaledHeight();
        float scale = hotbarScale(width, height);
        float right = hotbarRight(minecraft, width, scale);
        double x = (minecraft.mouseHandler.getScaledXPos(window) - right) / scale + 182;
        double y = (minecraft.mouseHandler.getScaledYPos(window) - (height - 4)) / scale + 23;
        return x >= 1 && x < 181 && y >= 0 && y < 22 ? (int) ((x - 1) / 20) : -1;
    }

    public static int hintBottom(Minecraft minecraft, int width, int height) {
        return isActive(minecraft) ? height - 8 - (int) Math.ceil(24 * hotbarScale(width, height)) : height - 8;
    }

    public static int hintLeft(Minecraft minecraft, int right) {
        return right - minecraft.font.width(LABEL) - 32;
    }

    public static void discardInventoryPresses(Minecraft minecraft) {
        while (minecraft.options.keyInventory.consumeClick()) {
            // Consume every queued press before vanilla processes the binding.
        }
    }
}
