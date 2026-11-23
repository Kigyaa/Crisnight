package ru.layoutcamera;

import java.io.IOException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.phys.BlockHitResult;

public final class Pets {
    private static final String PET_ID = "layoutcamera_pet";

    private Pets() {}

    static int give(CommandSourceStack source, String rawId) {
        ServerPlayer player = source.getPlayer();
        var server = player.level().getServer();
        if (!server.isSingleplayerOwner(player.nameAndId())) {
            var layout = StageCopies.layout(server, player.level().dimension(), player.getUUID());
            if (layout == null || !ZoneSelection.insideLayout(layout, player.position()) || player.isSpectator()) {
                source.sendFailure(Component.literal("Для выдачи защитника войди в свою копию уровня."));
                return 0;
            }
        }
        try {
            PetConfig pet = lookup(rawId.strip());
            var type = BuiltInRegistries.ENTITY_TYPE.getOptional(pet.model()).orElseThrow(() ->
                new IllegalArgumentException("Неизвестная модель моба: " + pet.model()));
            var item = BuiltInRegistries.ITEM.getOptional(pet.summonItem())
                .filter(candidate -> candidate != Items.AIR).orElseThrow(() ->
                    new IllegalArgumentException("Неизвестный предмет призыва: " + pet.summonItem()));
            ItemStack stack = new ItemStack(item);
            CompoundTag data = new CompoundTag();
            data.putString(PET_ID, pet.id().toString());
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
            Component name = type.getDescription();
            if (pet.values().has("name")) {
                name = Component.literal(pet.text("name", ""));
            }
            stack.set(DataComponents.CUSTOM_NAME, Component.literal("Призыв: ").append(name));
            LayoutCommands.giveItem(player, stack);
            source.sendSuccess(() -> Component.literal("Выдан предмет призыва: " + pet.id()), false);
            return 1;
        } catch (IOException | IllegalArgumentException exception) {
            source.sendFailure(Component.literal(exception.getMessage()));
            return 0;
        }
    }

    public static String summonId(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data == null ? null : data.copyTag().getString(PET_ID).orElse(null);
    }

    static Identifier planningId(ItemStack stack) {
        String summon = summonId(stack);
        if (summon != null) {
            return PetConfig.petId(summon);
        }
        return stack.is(Items.EGG) || stack.is(Items.CHICKEN_SPAWN_EGG) ? PetConfig.PLANNING_CHICKEN : null;
    }

    public static InteractionResult summon(ServerPlayer player, ServerLevel level, ItemStack stack, String id,
                                           BlockHitResult hit) {
        var position = hit.getBlockPos().relative(hit.getDirection());
        if (player.isSpectator() || !player.mayInteract(level, position)
            || !player.mayUseItemAt(position, hit.getDirection(), stack)) {
            return InteractionResult.FAIL;
        }
        try {
            PetConfig pet = lookup(id);
            var type = BuiltInRegistries.ENTITY_TYPE.getOptional(pet.model()).orElseThrow(() ->
                new IllegalArgumentException("Неизвестная модель моба: " + pet.model()));
            var entity = type.create(level, null, position, EntitySpawnReason.SPAWN_ITEM_USE, false, false);
            if (!(entity instanceof Mob mob)) {
                throw new IllegalArgumentException("Укажи доступный тип моба в model: " + pet.model());
            }
            configureSummonedMob(mob, pet);
            if (!level.noCollision(mob) || !level.tryAddFreshEntityWithPassengers(mob)) {
                throw new IllegalArgumentException("Мобу не хватает места для призыва.");
            }
            stack.consume(1, player);
            return InteractionResult.SUCCESS_SERVER;
        } catch (IOException | IllegalArgumentException exception) {
            player.sendOverlayMessage(Component.literal(exception.getMessage()));
            return InteractionResult.FAIL;
        }
    }

    private static PetConfig lookup(String rawId) throws IOException {
        Identifier id = PetConfig.petId(rawId);
        if (id != null) {
            PetConfig pet = PetConfig.load().get(id);
            if (pet != null) {
                return pet;
            }
        }
        throw new IllegalArgumentException("Неизвестный ID моба: " + rawId);
    }

    static void configureSummonedMob(Mob mob, PetConfig pet) {
        configureMob(mob, pet);
        FactionCombat.assign(mob, pet);
        if (!pet.canMove()) {
            mob.setNoAi(true);
        }
        mob.setPersistenceRequired();
        mob.addTag(PET_ID + "=" + pet.id());
    }

    static boolean isConfigured(Mob mob) {
        return mob.entityTags().stream().anyMatch(tag -> tag.startsWith(PET_ID + "="));
    }

    static void configureMob(Mob mob, PetConfig pet) {
        if (pet.values().has("name")) {
            mob.setCustomName(Component.literal(pet.text("name", "")));
        }
        apply(mob, Attributes.MAX_HEALTH, pet.number("health", 1));
        apply(mob, Attributes.ARMOR, pet.number("armor", 0));
        apply(mob, Attributes.ATTACK_DAMAGE, pet.number("damage", 0));
        apply(mob, Attributes.ATTACK_SPEED, pet.number("attack_speed", 0));
        Double movementSpeed = pet.movementSpeed();
        apply(mob, Attributes.MOVEMENT_SPEED, movementSpeed);
        apply(mob, Attributes.FLYING_SPEED, movementSpeed);
        mob.setHealth(mob.getMaxHealth());
    }

    private static void apply(Mob mob, Holder<Attribute> attribute, Double value) {
        var instance = mob.getAttribute(attribute);
        if (instance != null && value != null) {
            instance.setBaseValue(value);
        }
    }
}
