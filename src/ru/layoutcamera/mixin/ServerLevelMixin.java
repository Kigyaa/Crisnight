package ru.layoutcamera.mixin;

import java.util.function.BooleanSupplier;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.layoutcamera.ZoneSelection;
import ru.layoutcamera.SpawnZones;
import ru.layoutcamera.StageCopies;

@Mixin(ServerLevel.class)
abstract class ServerLevelMixin {
    @Inject(method = "close", at = @At("HEAD"))
    private void layoutcamera$closeSpawnRun(CallbackInfo callback) {
        ServerLevel level = (ServerLevel) (Object) this;
        SpawnZones.close(level);
        StageCopies.close(level);
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void layoutcamera$tickZones(BooleanSupplier hasTimeLeft, CallbackInfo callback) {
        ServerLevel level = (ServerLevel) (Object) this;
        StageCopies.tick(level);
        ZoneSelection.removeMobsOnWhiteZones(level);
        SpawnZones.tick(level);
    }
}
