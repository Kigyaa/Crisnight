package ru.layoutcamera.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.layoutcamera.Pets;

@Mixin(MultiPlayerGameMode.class)
abstract class MultiPlayerGameModeMixin {
    @Inject(method = "performUseItemOn", at = @At("HEAD"), cancellable = true)
    private void layoutcamera$skipVanillaItemPrediction(LocalPlayer player, InteractionHand hand, BlockHitResult hit,
                                                        CallbackInfoReturnable<InteractionResult> callback) {
        if (Minecraft.getInstance().getSingleplayerServer() != null && Pets.summonId(player.getItemInHand(hand)) != null) {
            callback.setReturnValue(InteractionResult.SUCCESS);
        }
    }
}
