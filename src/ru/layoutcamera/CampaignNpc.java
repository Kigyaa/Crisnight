package ru.layoutcamera;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;

public final class CampaignNpc {
    private static final String TAG = "layoutcamera_campaign_npc";
    private static final String NAME_KEY = "entity.layoutcamera.campaign_npc";
    private static final Component NAME = Component.translatableWithFallback(NAME_KEY, "Карта уровней");

    private CampaignNpc() {}

    static int create(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        var level = player.level();
        var npc = EntityTypes.VILLAGER.create(level, EntitySpawnReason.COMMAND);
        if (npc != null) {
            npc.snapTo(source.getPosition(), player.getYRot(), 0);
            npc.setYHeadRot(npc.getYRot());
            npc.setCustomName(NAME);
            npc.setCustomNameVisible(true);
            npc.setNoAi(true);
            npc.setSilent(true);
            npc.setPermanentlyInvulnerable(true);
            npc.setPersistenceRequired();
            npc.addTag(TAG);
            if (level.noCollision(npc) && level.addFreshEntity(npc)) {
                source.sendSuccess(() -> Component.literal("NPC «Карта уровней» создан. Нажми по нему ПКМ."), false);
                return 1;
            }
        }
        source.sendFailure(Component.literal("Не удалось создать NPC. Выбери свободное место."));
        return 0;
    }

    public static boolean isNpc(Entity entity) {
        return entity.getType() == EntityTypes.VILLAGER && entity.entityTags().contains(TAG);
    }

    public static boolean hasNpcName(Entity entity) {
        Component name = entity.getCustomName();
        return entity.getType() == EntityTypes.VILLAGER && name != null
            && name.getContents() instanceof TranslatableContents translated && NAME_KEY.equals(translated.getKey());
    }
}
