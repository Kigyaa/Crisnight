package ru.layoutcamera.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.layoutcamera.ZoneSelection;
import ru.layoutcamera.PlanningDrag;
import ru.layoutcamera.PlanningMobs;

@Mixin(LevelRenderer.class)
abstract class LevelRendererMixin {
    @Inject(method = "finalizeGizmoCollection", at = @At("HEAD"))
    private void layoutcamera$drawZones(CallbackInfo callback) {
        LevelRenderer renderer = (LevelRenderer) (Object) this;
        try (var ignored = renderer.collectPerFrameRenderThreadGizmos()) {
            ZoneSelection.draw(Minecraft.getInstance());
            PlanningMobs.drawSelectedAttackGrid(Minecraft.getInstance());
            PlanningDrag.drawArrow(Minecraft.getInstance());
        }
    }
}
