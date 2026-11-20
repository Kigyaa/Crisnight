package ru.layoutcamera.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.layoutcamera.Pets;

@Mixin(ServerPlayerGameMode.class)
abstract class ServerPlayerGameModeMixin {
    @Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
    private void layoutcamera$summonPet(ServerPlayer player, Level level, ItemStack stack, InteractionHand hand,
                                        BlockHitResult hit, CallbackInfoReturnable<InteractionResult> callback) {
        String id = Pets.summonId(stack);
        if (id != null && level instanceof ServerLevel server && server.getServer().isSingleplayer()) {
            callback.setReturnValue(Pets.summon(player, server, stack, id, hit));
        }
    }

    @Inject(method = "useItem", at = @At("HEAD"), cancellable = true)
    private void layoutcamera$preventVanillaItemUse(ServerPlayer player, Level level, ItemStack stack,
                                                   InteractionHand hand, CallbackInfoReturnable<InteractionResult> callback) {
        if (level instanceof ServerLevel server && server.getServer().isSingleplayer() && Pets.summonId(stack) != null) {
            callback.setReturnValue(InteractionResult.FAIL);
        }
    }
}
