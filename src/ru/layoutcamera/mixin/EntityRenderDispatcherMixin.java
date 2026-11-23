package ru.layoutcamera.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.layoutcamera.MobHealthRenderState;

@Mixin(EntityRenderDispatcher.class)
abstract class EntityRenderDispatcherMixin {
    @Inject(method = "extractEntity", at = @At("RETURN"))
    private void layoutcamera$captureMobHealth(Entity entity, float partialTick,
                                               CallbackInfoReturnable<EntityRenderState> callback) {
        var state = callback.getReturnValue();
        if (entity instanceof Mob mob && mob.isAlive() && !state.isInvisible && mob.getMaxHealth() > 0) {
            ((MobHealthRenderState) state).layoutcamera$setHealthFraction(
                Math.clamp(mob.getHealth() / mob.getMaxHealth(), 0, 1));
        } else {
            ((MobHealthRenderState) state).layoutcamera$setHealthFraction(0);
        }
    }

    @Inject(method = "submit", at = @At(value = "INVOKE", shift = At.Shift.AFTER, target =
        "Lnet/minecraft/client/renderer/entity/EntityRenderer;submit(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V"))
    private void layoutcamera$submitHealthBar(EntityRenderState state, CameraRenderState camera, double x, double y,
                                               double z, PoseStack pose, SubmitNodeCollector collector,
                                               CallbackInfo callback) {
        float fraction = ((MobHealthRenderState) state).layoutcamera$healthFraction();
        if (!(fraction > 0) || !camera.initialized || Minecraft.getInstance().gui.hud.isHidden()) {
            return;
        }

        double height = state.boundingBoxHeight + 0.23;
        if (state.nameTag != null && state.nameTagAttachment != null) {
            height = Math.max(height, state.nameTagAttachment.y + 0.34);
        }
        float halfWidth = Math.clamp(state.boundingBoxWidth * 0.5f, 0.42f, 0.8f);
        pose.pushPose();
        pose.translate(0, height, 0);
        pose.rotate(camera.orientation);
        collector.submitCustomGeometry(pose, RenderTypes.debugQuads(), (matrix, vertices) ->
            layoutcamera$drawHealthBar(matrix, vertices, halfWidth, fraction));
        pose.popPose();
    }

    @Unique
    private static void layoutcamera$drawHealthBar(PoseStack.Pose matrix, VertexConsumer vertices,
                                                    float halfWidth, float fraction) {
        float edge = 0.013f;
        float height = 0.10f;
        float left = -halfWidth + edge;
        float right = halfWidth - edge;
        float filled = left + (right - left) * fraction;
        int border = 0xff17191f;
        int color = fraction > 0.55f ? 0xff65e38a : fraction > 0.25f ? 0xffffc45b : 0xffef5d65;

        layoutcamera$quad(matrix, vertices, -halfWidth, 0, halfWidth, edge, border);
        layoutcamera$quad(matrix, vertices, -halfWidth, height - edge, halfWidth, height, border);
        layoutcamera$quad(matrix, vertices, -halfWidth, edge, left, height - edge, border);
        layoutcamera$quad(matrix, vertices, right, edge, halfWidth, height - edge, border);
        layoutcamera$quad(matrix, vertices, left, edge, filled, height - edge, color);
        if (fraction < 1) {
            layoutcamera$quad(matrix, vertices, filled, edge, right, height - edge, 0xff482830);
        }
    }

    @Unique
    private static void layoutcamera$quad(PoseStack.Pose pose, VertexConsumer vertices, float left, float bottom,
                                          float right, float top, int color) {
        vertices.addVertex(pose, left, bottom, 0).setColor(color);
        vertices.addVertex(pose, right, bottom, 0).setColor(color);
        vertices.addVertex(pose, right, top, 0).setColor(color);
        vertices.addVertex(pose, left, top, 0).setColor(color);
    }
}
