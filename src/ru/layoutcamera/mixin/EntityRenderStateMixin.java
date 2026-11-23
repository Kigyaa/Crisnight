package ru.layoutcamera.mixin;

import net.minecraft.client.renderer.entity.state.EntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import ru.layoutcamera.MobHealthRenderState;

@Mixin(EntityRenderState.class)
abstract class EntityRenderStateMixin implements MobHealthRenderState {
    @Unique
    private float layoutcamera$healthFraction;

    @Override
    public float layoutcamera$healthFraction() {
        return layoutcamera$healthFraction;
    }

    @Override
    public void layoutcamera$setHealthFraction(float fraction) {
        layoutcamera$healthFraction = fraction;
    }
}
