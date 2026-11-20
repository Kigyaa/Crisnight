package ru.layoutcamera.mixin;

import net.minecraft.commands.Commands;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.layoutcamera.LayoutCommands;

@Mixin(Commands.class)
abstract class CommandsMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void layoutcamera$registerCommands(CallbackInfo callback) {
        LayoutCommands.register(((Commands) (Object) this).getDispatcher());
    }
}
