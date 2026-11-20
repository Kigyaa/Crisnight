package ru.layoutcamera;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import ru.layoutcamera.LocalLevels.LayoutLevel;

public final class StagePlanning {
    private static Remote remote;

    private StagePlanning() {}

    public record StatePacket(String json) implements CustomPacketPayload {
        public static final Type<StatePacket> TYPE = new Type<>(Identifier.parse("layoutcamera:stage_planning"));
        public static final StreamCodec<FriendlyByteBuf, StatePacket> CODEC =
            CustomPacketPayload.codec((packet, buf) -> buf.writeUtf(packet.json, 262144),
                buf -> new StatePacket(buf.readUtf(262144)));

        @Override
        public Type<StatePacket> type() {
            return TYPE;
        }
    }

    public record EditPacket(UUID scope, Vec3 center, float yaw, int slot) implements CustomPacketPayload {
        public static final Type<EditPacket> TYPE = new Type<>(Identifier.parse("layoutcamera:stage_edit"));
        public static final StreamCodec<FriendlyByteBuf, EditPacket> CODEC =
            CustomPacketPayload.codec((packet, buf) -> {
                buf.writeUUID(packet.scope);
                buf.writeDouble(packet.center.x);
                buf.writeDouble(packet.center.y);
                buf.writeDouble(packet.center.z);
                buf.writeFloat(packet.yaw);
                buf.writeByte(packet.slot);
            }, buf -> new EditPacket(buf.readUUID(), new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()),
                buf.readFloat(), buf.readByte()));

        @Override
        public Type<EditPacket> type() {
            return TYPE;
        }
    }

    static void send(ServerPlayer player, LayoutLevel layout) {
        if (player.level().getServer().isSingleplayerOwner(player.nameAndId())) {
            return;
        }
        JsonObject state = new JsonObject();
        if (layout != null) {
            state.addProperty("dimension", player.level().dimension().identifier().toString());
            JsonObject geometry = LocalLevels.writeLayout(layout).deepCopy();
            geometry.getAsJsonObject("zones").entrySet().forEach(entry -> {
                var zone = entry.getValue().getAsJsonObject();
                if (zone.get("type").getAsString().equals("red")) {
                    zone.getAsJsonObject("settings").keySet().removeIf(key -> !key.equals("enabled"));
                }
            });
            state.add("layout", geometry);
            JsonArray mobs = new JsonArray();
            for (var mob : PlanningMobs.defenderPlacements(player.level(), layout.id())) {
                JsonObject item = new JsonObject();
                item.addProperty("id", mob.id().toString());
                item.addProperty("x", mob.center().x);
                item.addProperty("y", mob.center().y);
                item.addProperty("z", mob.center().z);
                item.addProperty("yaw", mob.yaw());
                item.addProperty("zone", mob.zone().name());
                mobs.add(item);
            }
            state.add("mobs", mobs);
            JsonObject pets = new JsonObject();
            try {
                PetConfig.load().forEach((id, pet) -> pets.add(id.toString(), pet.values()));
            } catch (IOException exception) {
                LogUtils.getLogger().warn("Could not load mob definitions for a LAN stage", exception);
            }
            state.add("pets", pets);
        }
        String json = state.toString();
        if (json.length() > 262144) {
            player.sendSystemMessage(Component.literal("Расстановка уровня слишком велика для передачи клиенту."));
            return;
        }
        player.connection.send(new ClientboundCustomPayloadPacket(new StatePacket(json)));
    }

    public static void receive(ClientPacketListener connection, StatePacket packet) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getConnection() != connection || minecraft.getSingleplayerServer() != null) {
            return;
        }
        try {
            JsonObject state = JsonParser.parseString(packet.json()).getAsJsonObject();
            LayoutLevel layout = state.has("layout") ? LocalLevels.readLayout(state.getAsJsonObject("layout")) : null;
            List<StageFiles.Placement> placements = layout == null ? List.of() : state.getAsJsonArray("mobs").asList().stream()
                .map(value -> {
                    var item = value.getAsJsonObject();
                    return new StageFiles.Placement(Identifier.parse(item.get("id").getAsString()),
                        new Vec3(item.get("x").getAsDouble(), item.get("y").getAsDouble(), item.get("z").getAsDouble()),
                        item.get("yaw").getAsFloat(), ZoneSelection.ZoneColor.valueOf(item.get("zone").getAsString()));
                }).toList();
            Map<Identifier, PetConfig> definitions = new LinkedHashMap<>();
            if (layout != null) {
                state.getAsJsonObject("pets").entrySet().forEach(entry -> {
                    Identifier id = Identifier.parse(entry.getKey());
                    var json = entry.getValue().getAsJsonObject();
                    definitions.put(id, new PetConfig(id, Identifier.parse(json.get("model").getAsString()),
                        Identifier.parse("minecraft:clay_ball"), json));
                });
            }
            if (remote != null) {
                PlanningMobs.removeStage(remote.layout.id());
            }
            remote = layout == null ? null : new Remote(connection, state.get("dimension").getAsString(),
                layout, Map.copyOf(definitions));
            if (layout != null) {
                PlanningMobs.addStage(layout.id(), placements, Vec3.ZERO);
            }
        } catch (RuntimeException exception) {
            LogUtils.getLogger().warn("Could not read LAN stage planning", exception);
        }
    }

    static LayoutLevel clientLayout(Minecraft minecraft) {
        if (remote != null && remote.connection != minecraft.getConnection()) {
            PlanningMobs.removeStage(remote.layout.id());
            remote = null;
        }
        return remote != null && minecraft.level != null
            && minecraft.level.dimension().identifier().toString().equals(remote.dimension) ? remote.layout : null;
    }

    static Map<Identifier, PetConfig> clientPets(Minecraft minecraft) {
        return clientLayout(minecraft) == null ? null : remote.definitions;
    }

    static boolean edit(Minecraft minecraft, UUID scope, Vec3 center, float yaw, int slot) {
        LayoutLevel layout = clientLayout(minecraft);
        if (layout == null || !layout.id().equals(scope)) {
            return false;
        }
        minecraft.getConnection().send(new ServerboundCustomPayloadPacket(new EditPacket(scope, center, yaw, slot)));
        return true;
    }

    public static void receive(ServerPlayer player, EditPacket packet) {
        var server = player.level().getServer();
        server.execute(() -> {
            if (!server.isSingleplayer() || server.getPlayerList().getPlayer(player.getUUID()) != player
                || player.isSpectator()) {
                return;
            }
            LayoutLevel layout = StageCopies.layout(server, player.level().dimension(), player.getUUID());
            if (layout == null || !layout.id().equals(packet.scope())
                || !LocalLevels.isNear(layout.bounds(), player.position(), 20)) {
                return;
            }
            if (packet.slot() == -1) {
                if (PlanningMobs.removeStagePlacement(layout.id(), packet.center())) {
                    send(player, layout);
                }
                return;
            }
            if (packet.slot() < 0 || packet.slot() >= 9 || !Float.isFinite(packet.yaw()) || packet.yaw() % 90 != 0) {
                return;
            }
            Identifier id = Pets.planningId(player.getInventory().getItem(packet.slot()));
            if (id == null) {
                return;
            }
            try {
                PetConfig pet = PetConfig.load().get(id);
                var zone = PlanningMobs.allowedZone(id, pet);
                if (zone != null && ZoneSelection.permitsStagePlacement(layout, packet.center(), zone, id)
                    && PlanningMobs.placeStageMob(layout.id(), new StageFiles.Placement(id, packet.center(),
                        packet.yaw() % 360, zone))) {
                    send(player, layout);
                }
            } catch (IOException | RuntimeException exception) {
                LogUtils.getLogger().warn("Could not place a LAN stage defender", exception);
            }
        });
    }

    private record Remote(ClientPacketListener connection, String dimension, LayoutLevel layout,
                          Map<Identifier, PetConfig> definitions) {}
}
