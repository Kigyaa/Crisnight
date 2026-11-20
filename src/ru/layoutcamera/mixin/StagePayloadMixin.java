package ru.layoutcamera.mixin;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload.TypeAndCodec;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import ru.layoutcamera.StagePlanning;

@Mixin(ClientboundCustomPayloadPacket.class)
abstract class StagePayloadMixin {
    @ModifyArg(method = "<clinit>", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;codec(Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload$FallbackProvider;Ljava/util/List;)Lnet/minecraft/network/codec/StreamCodec;",
        ordinal = 0), index = 1)
    private static List<TypeAndCodec<?, ?>> layoutcamera$registerStageState(List<TypeAndCodec<?, ?>> codecs) {
        var updated = new ArrayList<>(codecs);
        updated.add(new TypeAndCodec<>(StagePlanning.StatePacket.TYPE, StagePlanning.StatePacket.CODEC));
        return updated;
    }
}

@Mixin(ServerboundCustomPayloadPacket.class)
abstract class StageEditPayloadMixin {
    @ModifyArg(method = "<clinit>", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;codec(Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload$FallbackProvider;Ljava/util/List;)Lnet/minecraft/network/codec/StreamCodec;"),
        index = 1)
    private static List<TypeAndCodec<?, ?>> layoutcamera$registerStageEdit(List<TypeAndCodec<?, ?>> codecs) {
        var updated = new ArrayList<>(codecs);
        updated.add(new TypeAndCodec<>(StagePlanning.EditPacket.TYPE, StagePlanning.EditPacket.CODEC));
        return updated;
    }
}
