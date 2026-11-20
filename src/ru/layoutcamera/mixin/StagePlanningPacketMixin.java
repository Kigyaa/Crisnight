package ru.layoutcamera.mixin;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.layoutcamera.StagePlanning;

@Mixin(ClientPacketListener.class)
abstract class StagePlanningPacketMixin {
    @Inject(method = "handleCustomPayload(Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;)V",
        at = @At("HEAD"), cancellable = true)
    private void layoutcamera$receiveStageState(CustomPacketPayload payload, CallbackInfo callback) {
        if (payload instanceof StagePlanning.StatePacket state) {
            StagePlanning.receive((ClientPacketListener) (Object) this, state);
            callback.cancel();
        }
    }
}

@Mixin(ServerGamePacketListenerImpl.class)
abstract class StageEditPacketMixin {
    @Shadow public ServerPlayer player;

    @Inject(method = "handleCustomPayload", at = @At("HEAD"), cancellable = true)
    private void layoutcamera$receiveStageEdit(ServerboundCustomPayloadPacket packet, CallbackInfo callback) {
        if (packet.payload() instanceof StagePlanning.EditPacket edit) {
            StagePlanning.receive(player, edit);
            callback.cancel();
        }
    }
}
