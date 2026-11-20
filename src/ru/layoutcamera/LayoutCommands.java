package ru.layoutcamera;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.io.IOException;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class LayoutCommands {
    private LayoutCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("givepet")
            .requires(LayoutCommands::canUseStageCopies)
            .then(Commands.argument("id", StringArgumentType.greedyString())
                .executes(context -> Pets.give(context.getSource(), StringArgumentType.getString(context, "id")))));
        dispatcher.register(Commands.literal("givetool")
            .requires(LayoutCommands::isWorldOwner)
            .executes(context -> giveTool(context.getSource())));
        dispatcher.register(Commands.literal("startspawn")
            .requires(LayoutCommands::isWorldOwner)
            .executes(context -> SpawnZones.start(context.getSource())));
        dispatcher.register(Commands.literal("createstage")
            .requires(LayoutCommands::canUseStageCopies)
            .then(Commands.argument("name", StringArgumentType.word())
                .executes(context -> StageCopies.create(context.getSource(), StringArgumentType.getString(context, "name")))));
        dispatcher.register(Commands.literal("removestage")
            .requires(LayoutCommands::isWorldOwner)
            .then(Commands.argument("name", StringArgumentType.word())
                .executes(context -> StageCopies.remove(context.getSource(), StringArgumentType.getString(context, "name")))));
        dispatcher.register(Commands.literal("removestagecopy")
            .requires(LayoutCommands::canUseStageCopies)
            .then(Commands.argument("name", StringArgumentType.word())
                .executes(context -> StageCopies.removeCopy(context.getSource(), StringArgumentType.getString(context, "name")))));
        dispatcher.register(Commands.literal("layoutlevel")
            .requires(LayoutCommands::isWorldOwner)
            .executes(context -> onHost(context.getSource(), ZoneSelection::listLayouts))
            .then(Commands.literal("list")
                .executes(context -> onHost(context.getSource(), ZoneSelection::listLayouts)))
            .then(Commands.literal("create").then(Commands.argument("name", StringArgumentType.word())
                .executes(context -> onHost(context.getSource(), minecraft ->
                    ZoneSelection.beginLevelDraft(minecraft, StringArgumentType.getString(context, "name"))))))
            .then(Commands.literal("cancel").executes(context -> onHost(context.getSource(), minecraft -> {
                ZoneSelection.cancelLevelDraft();
                ZoneSelection.tell(minecraft, "Выделение границы отменено.");
            })))
            .then(Commands.literal("zones").then(Commands.argument("name", StringArgumentType.word())
                .executes(context -> onHost(context.getSource(), minecraft ->
                    ZoneSelection.listZones(minecraft, StringArgumentType.getString(context, "name"))))))
            .then(Commands.literal("zone").then(Commands.argument("name", StringArgumentType.word())
                .then(Commands.argument("zone", StringArgumentType.word())
                    .then(Commands.argument("settings", StringArgumentType.greedyString())
                        .executes(context -> onHost(context.getSource(), minecraft ->
                            ZoneSelection.configureZone(minecraft, StringArgumentType.getString(context, "name"),
                                StringArgumentType.getString(context, "zone"),
                                StringArgumentType.getString(context, "settings"))))))))
            .then(Commands.literal("delete").then(Commands.argument("name", StringArgumentType.word())
                .executes(context -> StageCopies.deleteDraft(context.getSource(), StringArgumentType.getString(context, "name")))))
            .then(Commands.literal("reload")
                .executes(context -> onHost(context.getSource(), ZoneSelection::reloadLayouts))));
    }

    private static int onHost(CommandSourceStack source, LevelAction action) {
        Minecraft minecraft = Minecraft.getInstance();
        var server = source.getServer();
        var dimension = source.getPlayer().level().dimension();
        minecraft.execute(() -> {
            if (minecraft.player == null || minecraft.level == null || minecraft.getSingleplayerServer() != server
                || !minecraft.level.dimension().equals(dimension)) {
                return;
            }
            ZoneSelection.loadLevel(minecraft);
            try {
                action.run(minecraft);
            } catch (IOException | RuntimeException exception) {
                ZoneSelection.tell(minecraft, "Уровни · " + exception.getMessage());
            }
        });
        return 1;
    }

    @FunctionalInterface
    private interface LevelAction {
        void run(Minecraft minecraft) throws IOException;
    }

    private static boolean isWorldOwner(CommandSourceStack source) {
        var server = source.getServer();
        ServerPlayer player = source.getPlayer();
        return server != null && server.isSingleplayer() && player != null && server.isSingleplayerOwner(player.nameAndId());
    }

    private static boolean canUseStageCopies(CommandSourceStack source) {
        var server = source.getServer();
        return server != null && server.isSingleplayer() && source.getPlayer() != null;
    }

    private static int giveTool(CommandSourceStack source) {
        ItemStack tool = new ItemStack(Items.STICK);
        tool.set(DataComponents.CUSTOM_NAME, Component.literal(ZoneSelection.TOOL_NAME));
        giveItem(source.getPlayer(), tool);
        source.sendSuccess(() -> Component.literal("Выдан предмет «" + ZoneSelection.TOOL_NAME + "»"), false);
        return 1;
    }

    static void giveItem(ServerPlayer player, ItemStack stack) {
        player.getInventory().add(stack);
        if (!stack.isEmpty()) {
            player.spawnAtLocation(player.level(), stack);
        }
    }
}
