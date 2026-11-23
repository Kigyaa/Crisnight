package ru.layoutcamera.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.layoutcamera.Campaign;
import ru.layoutcamera.CampaignNpc;

@Mixin(Player.class)
abstract class PlayerMixin {
    @Inject(method = "interactOn", at = @At("HEAD"), cancellable = true)
    private void layoutcamera$openCampaign(Entity target, InteractionHand hand, Vec3 location,
                                           CallbackInfoReturnable<InteractionResult> callback) {
        if ((Object) this instanceof ServerPlayer player) {
            if (CampaignNpc.isNpc(target)) {
                if (hand == InteractionHand.MAIN_HAND) {
                    Campaign.openFor(player);
                }
                callback.setReturnValue(InteractionResult.CONSUME);
            }
        } else if (CampaignNpc.hasNpcName(target)) {
            callback.setReturnValue(InteractionResult.CONSUME);
        }
    }
}
