package ru.layoutcamera.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.layoutcamera.CampaignNpc;
import ru.layoutcamera.LayoutCamera;
import ru.layoutcamera.ZoneSelection;
import ru.layoutcamera.PlanningDrag;
import ru.layoutcamera.PlanningMobs;
import ru.layoutcamera.Pets;
import ru.layoutcamera.RouteParticles;

@Mixin(Minecraft.class)
abstract class MinecraftMixin {
    @Shadow private int rightClickDelay;

    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void layoutcamera$useSummoningItem(CallbackInfo callback) {
        Minecraft minecraft = (Minecraft) (Object) this;
        if (minecraft.getSingleplayerServer() == null || minecraft.player == null || minecraft.gameMode == null
            || minecraft.player.isSpectator()
            || (minecraft.hitResult instanceof EntityHitResult hit && CampaignNpc.hasNpcName(hit.getEntity()))) {
            return;
        }
        for (InteractionHand hand : InteractionHand.values()) {
            if (Pets.summonId(minecraft.player.getItemInHand(hand)) == null) {
                continue;
            }
            rightClickDelay = 4;
            if (minecraft.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
                minecraft.gameMode.useItemOn(minecraft.player, hand, hit);
            } else {
                minecraft.player.sendOverlayMessage(Component.literal("Нажми ПКМ по блоку, чтобы призвать моба."));
            }
            callback.cancel();
            return;
        }
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void layoutcamera$loadZones(CallbackInfo callback) {
        Minecraft minecraft = (Minecraft) (Object) this;
        ZoneSelection.loadLevel(minecraft);
        ZoneSelection.tickRoutePreview(minecraft);
        RouteParticles.tick(minecraft);
        PlanningMobs.tick(minecraft);
        if (PlanningDrag.active() && (!minecraft.isWindowActive() || minecraft.gui.screen() != null
            || minecraft.gui.overlay() != null || !LayoutCamera.isActive(minecraft))) {
            PlanningDrag.cancel();
        }
    }

    @Inject(method = "clearClientLevel", at = @At("HEAD"))
    private void layoutcamera$clearZones(CallbackInfo callback) {
        ZoneSelection.reset();
        PlanningMobs.reset();
        RouteParticles.reset();
    }

    @Inject(method = "handleKeybinds", at = @At("HEAD"))
    private void layoutcamera$blockInventory(CallbackInfo callback) {
        Minecraft minecraft = (Minecraft) (Object) this;
        if (LayoutCamera.isActive(minecraft)) {
            LayoutCamera.discardInventoryPresses(minecraft);
        }
    }
}
