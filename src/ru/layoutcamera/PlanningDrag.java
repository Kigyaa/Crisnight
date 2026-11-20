package ru.layoutcamera;

import java.io.IOException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public final class PlanningDrag {
    private static volatile ItemStack stack = ItemStack.EMPTY;
    private static volatile DraggedMob mob;
    private static ClientLevel origin;
    private static float previewYaw;

    private PlanningDrag() {}

    public static void begin(Minecraft minecraft, int slot) {
        cancel();
        ItemStack item = minecraft.player.getInventory().getItem(slot);
        Identifier id = Pets.planningId(item);
        if (id == null) {
            stack = item.copy();
            origin = minecraft.level;
            return;
        }
        try {
            var allowed = PlanningMobs.allowedZone(id);
            if (allowed == null) {
                minecraft.player.sendOverlayMessage(Component.literal("Размещение этого моба отключено или его ID отсутствует в mobs.json."));
                return;
            }
            Mob model = PlanningMobs.createDragModel(minecraft, id);
            if (model == null) {
                minecraft.player.sendOverlayMessage(Component.literal("Не удалось загрузить модель моба " + id));
                return;
            }
            previewYaw = LayoutCamera.yaw() + 180.0f;
            mob = new DraggedMob(id, allowed, slot, model);
        } catch (IOException exception) {
            minecraft.player.sendOverlayMessage(Component.literal("Не удалось прочитать mobs.json: " + exception.getMessage()));
            return;
        }
        origin = minecraft.level;
    }

    public static boolean active() {
        return mob != null || !stack.isEmpty();
    }

    static ZoneSelection.ZoneColor placementColor() {
        DraggedMob dragged = mob;
        return dragged == null ? null : dragged.zone();
    }

    static Identifier placementId() {
        DraggedMob dragged = mob;
        return dragged == null ? null : dragged.id();
    }

    public static void cancel() {
        stack = ItemStack.EMPTY;
        mob = null;
        origin = null;
    }

    public static void release(Minecraft minecraft) {
        DraggedMob dragged = mob;
        if (origin == minecraft.level && LayoutCamera.isActive(minecraft) && dragged != null) {
            ZoneSelection.MobPlacement target = ZoneSelection.mobPlacement(minecraft, dragged.zone(), dragged.id());
            if (target != null) {
                if (!target.temporary() || !StagePlanning.edit(minecraft, target.scope(), target.center(),
                    target.yaw(), dragged.slot())) {
                    PlanningMobs.place(minecraft, target, dragged.id());
                }
            } else {
                minecraft.player.sendOverlayMessage(Component.literal("Нужна свободная точка размещения · " + dragged.zone().zoneName()));
            }
        }
        cancel();
    }

    public static void drawCursorPreview(Minecraft minecraft, GuiGraphicsExtractor graphics) {
        DraggedMob dragged = mob;
        ItemStack draggedItem = stack;
        if ((dragged == null && draggedItem.isEmpty()) || !LayoutCamera.isActive(minecraft)) {
            return;
        }
        var window = minecraft.getWindow();
        int x = (int) minecraft.mouseHandler.getScaledXPos(window);
        int y = (int) minecraft.mouseHandler.getScaledYPos(window);
        graphics.nextStratum();
        if (dragged == null) {
            graphics.item(draggedItem, x + 5, y - 8);
            return;
        }
        Mob model = dragged.model();
        int scale = Math.clamp(Math.round(28 / Math.max(model.getBbWidth(), model.getBbHeight())), 1, 96);
        int halfSize = 24;
        int centerX = x + (x + 20 + halfSize > graphics.guiWidth() ? -20 : 20);
        int centerY = Math.clamp(y, halfSize, Math.max(halfSize, graphics.guiHeight() - halfSize));
        var target = ZoneSelection.mobPlacement(minecraft, dragged.zone(), dragged.id());
        if (target != null) {
            previewYaw = target.yaw();
        }
        float facing = Math.floorMod(Math.round(previewYaw - LayoutCamera.yaw()), 360);
        var state = minecraft.getEntityRenderDispatcher().getRenderer(model).createRenderState(model, 1.0f);
        state.shadowPieces.clear();
        state.outlineColor = 0;
        var pitch = new Quaternionf().rotateX((float) Math.toRadians(-10));
        var rotation = new Quaternionf().rotateZ((float) Math.PI).mul(pitch);
        if (state instanceof LivingEntityRenderState living) {
            living.bodyRot = facing;
            living.yRot = 0;
            living.xRot = 0;
            living.boundingBoxWidth /= living.scale;
            living.boundingBoxHeight /= living.scale;
            living.scale = 1;
        } else {
            rotation.rotateY((float) Math.toRadians(180 - facing));
        }
        graphics.entity(state, scale, new Vector3f(0, state.boundingBoxHeight / 2, 0), rotation, pitch,
            centerX - halfSize, centerY - halfSize, centerX + halfSize, centerY + halfSize);
    }

    public static void drawArrow(Minecraft minecraft) {
        DraggedMob dragged = mob;
        if (!LayoutCamera.isActive(minecraft) || dragged == null
            || minecraft.gui.screen() != null || minecraft.gui.overlay() != null) {
            return;
        }
        ZoneSelection.MobPlacement target = ZoneSelection.mobPlacement(minecraft, dragged.zone(), dragged.id());
        if (target != null) {
            Vec3 direction = Vec3.directionFromRotation(0, target.yaw());
            Vec3 side = new Vec3(-direction.z, 0, direction.x);
            Vec3 center = target.center().add(0, 0.015, 0);
            Vec3 tip = center.add(direction.scale(0.69));
            Vec3 notch = center.add(direction.scale(0.44));
            Vec3 shoulders = center.add(direction.scale(0.26));
            Vec3 innerShoulders = center.add(direction.scale(0.18));
            GizmoStyle style = GizmoStyle.fill(0xffffa600);
            Vec3 left = shoulders.add(side.scale(0.32));
            Vec3 right = shoulders.subtract(side.scale(0.32));
            Vec3 innerLeft = innerShoulders.add(side.scale(0.21));
            Vec3 innerRight = innerShoulders.subtract(side.scale(0.21));
            Gizmos.rect(tip, left, innerLeft, notch, style);
            Gizmos.rect(tip, notch, innerRight, right, style);
            Vec3[] outline = {tip, left, innerLeft, notch, innerRight, right};
            for (int i = 0; i < outline.length; i++) {
                Vec3 from = outline[i].add(0, 0.002, 0);
                Vec3 to = outline[(i + 1) % outline.length].add(0, 0.002, 0);
                Gizmos.line(from, to, 0xffffffff, 2.0f);
            }
        }
    }

    private record DraggedMob(Identifier id, ZoneSelection.ZoneColor zone, int slot, Mob model) {}

}
