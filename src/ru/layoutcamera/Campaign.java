package ru.layoutcamera;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

public final class Campaign {
    static final int ESCAPE_LIMIT = 3;
    private static final Path CONFIG = PetConfig.PROJECT_DIRECTORY.resolve("config/layout-camera/campaign.json");
    private static volatile Status hud;

    private Campaign() {}

    public static void open(Minecraft minecraft) {
        var server = minecraft.getSingleplayerServer();
        var player = minecraft.player;
        if (player == null) {
            return;
        }
        if (server == null) {
            ZoneSelection.tell(minecraft, "Карта уровней доступна хозяину локального мира.");
            return;
        }
        UUID playerId = player.getUUID();
        server.execute(() -> {
            ServerPlayer owner = owner(server, playerId);
            if (owner != null) {
                openFor(owner);
            }
        });
    }

    public static void openFor(ServerPlayer player) {
        var server = player.level().getServer();
        UUID playerId = player.getUUID();
        if (!server.isSingleplayerOwner(player.nameAndId())) {
            player.sendSystemMessage(Component.literal("Карта уровней доступна хозяину локального мира."));
            return;
        }
        if (!player.isAlive() || player.isSpectator()) {
            return;
        }
        try {
            Menu menu = loadMenu(CONFIG, progressPath(server, playerId));
            Minecraft minecraft = Minecraft.getInstance();
            minecraft.execute(() -> {
                if (matchesClient(minecraft, server, playerId)
                    && minecraft.gui.screen() == null && minecraft.gui.overlay() == null) {
                    minecraft.gui.setScreen(new CampaignScreen(menu));
                }
            });
        } catch (IOException | RuntimeException exception) {
            report(player, "Не удалось открыть карту уровней", exception);
        }
    }

    static void start(Minecraft minecraft, CampaignScreen screen, LevelEntry selected) {
        var server = minecraft.getSingleplayerServer();
        var player = minecraft.player;
        if (server == null || player == null) {
            screen.launchFailed();
            return;
        }
        UUID playerId = player.getUUID();
        server.execute(() -> {
            boolean started = false;
            ServerPlayer owner = owner(server, playerId);
            if (owner != null && owner.isAlive() && !owner.isSpectator()) {
                try {
                    LevelEntry entry = loadMenu(CONFIG, progressPath(server, playerId)).visible().stream()
                        .filter(candidate -> candidate.stage().equals(selected.stage())).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("Уровень скрыт. Открой карту заново."));
                    started = StageCopies.create(owner.createCommandSourceStack(), entry.stage(), entry) != 0;
                } catch (IOException | RuntimeException exception) {
                    report(owner, "Не удалось запустить уровень", exception);
                }
            }
            boolean success = started;
            minecraft.execute(() -> {
                if (minecraft.gui.screen() != screen) {
                    return;
                }
                if (success) {
                    minecraft.gui.setScreen(null);
                } else {
                    screen.launchFailed();
                }
            });
        });
    }

    private static ServerPlayer owner(MinecraftServer server, UUID playerId) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        return player != null && server.isSingleplayerOwner(player.nameAndId()) ? player : null;
    }

    static Menu loadMenu(Path config, Path progress) throws IOException {
        Set<String> completed = readProgress(progress);
        List<LevelEntry> visible = new ArrayList<>();
        for (LevelEntry entry : loadEntries(config)) {
            visible.add(entry);
            if (!completed.contains(entry.stage())) {
                break;
            }
        }
        return new Menu(List.copyOf(visible), completed);
    }

    private static List<LevelEntry> loadEntries(Path path) throws IOException {
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            var levels = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonArray("levels");
            if (levels == null) {
                throw new IllegalArgumentException("Укажи массив levels.");
            }
            List<LevelEntry> entries = new ArrayList<>();
            Set<String> names = new HashSet<>();
            for (var level : levels) {
                var object = level.getAsJsonObject();
                String stage = requiredString(object.get("stage"), "stage");
                StageFiles.directory(stage);
                if (!names.add(stage)) {
                    throw new IllegalArgumentException("Уровень указан дважды: " + stage);
                }
                String title = object.has("title") ? requiredString(object.get("title"), "title") : stage;
                entries.add(new LevelEntry(stage, title, coordinate(object.get("x"), "x"),
                    coordinate(object.get("y"), "y")));
            }
            return List.copyOf(entries);
        } catch (RuntimeException exception) {
            throw new IOException(path.getFileName() + " · " + exception.getMessage(), exception);
        }
    }

    private static String requiredString(JsonElement value, String field) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
            || value.getAsString().isBlank()) {
            throw new IllegalArgumentException("В поле " + field + " нужна непустая строка.");
        }
        return value.getAsString();
    }

    private static Double coordinate(JsonElement value, String field) {
        if (value == null) {
            return null;
        }
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
            double position = value.getAsDouble();
            if (position >= 0 && position <= 100) {
                return position;
            }
        }
        throw new IllegalArgumentException("В поле " + field + " нужна координата от 0 до 100.");
    }

    private static Path progressPath(MinecraftServer server, UUID player) {
        return server.getWorldPath(LevelResource.ROOT).resolve("layoutcamera-campaign-" + player + ".nbt");
    }

    private static Set<String> readProgress(Path path) throws IOException {
        if (Files.notExists(path)) {
            return Set.of();
        }
        var progress = StageFiles.readNbt(path).getCompound("completed")
            .orElseThrow(() -> new IOException("Файл прогресса повреждён: " + path.getFileName()));
        Set<String> completed = new HashSet<>();
        for (String name : progress.keySet()) {
            if (!progress.getBoolean(name).orElse(false)) {
                throw new IOException("Файл прогресса повреждён: " + path.getFileName());
            }
            completed.add(name);
        }
        return Set.copyOf(completed);
    }

    static boolean saveVictory(ServerPlayer player, LevelEntry entry) {
        try {
            Path path = progressPath(player.level().getServer(), player.getUUID());
            Set<String> completed = readProgress(path);
            if (!completed.contains(entry.stage())) {
                CompoundTag names = new CompoundTag();
                completed.forEach(name -> names.putBoolean(name, true));
                names.putBoolean(entry.stage(), true);
                CompoundTag progress = new CompoundTag();
                progress.put("completed", names);
                StageFiles.writeNbt(path, progress);
            }
            return true;
        } catch (IOException | RuntimeException exception) {
            report(player, "Уровень пройден, но сохранить прогресс не удалось. Для записи результата пройди уровень ещё раз",
                exception);
            return false;
        }
    }

    static void showResult(ServerPlayer player, Result result) {
        Minecraft minecraft = Minecraft.getInstance();
        var server = player.level().getServer();
        UUID playerId = player.getUUID();
        minecraft.execute(() -> {
            if (matchesClient(minecraft, server, playerId)) {
                minecraft.gui.setScreen(new CampaignResultScreen(result));
            }
        });
    }

    static void closeResult(ServerPlayer player, Result result) {
        Minecraft minecraft = Minecraft.getInstance();
        var server = player.level().getServer();
        UUID playerId = player.getUUID();
        minecraft.execute(() -> {
            if (matchesClient(minecraft, server, playerId)
                && minecraft.gui.screen() instanceof CampaignResultScreen screen && screen.result() == result) {
                minecraft.gui.setScreen(null);
            }
        });
    }

    static void actOnDefeat(Minecraft minecraft, CampaignResultScreen screen, boolean restart) {
        var server = minecraft.getSingleplayerServer();
        if (server == null || minecraft.player == null) {
            screen.actionFailed();
            return;
        }
        UUID playerId = minecraft.player.getUUID();
        server.execute(() -> {
            ServerPlayer player = owner(server, playerId);
            boolean success = player != null && player.isAlive()
                && StageCopies.actOnDefeat(player, screen.result(), restart);
            minecraft.execute(() -> {
                if (matchesClient(minecraft, server, playerId) && minecraft.gui.screen() == screen) {
                    if (success) {
                        minecraft.gui.setScreen(null);
                    } else {
                        screen.actionFailed();
                    }
                }
            });
        });
    }

    private static boolean matchesClient(Minecraft minecraft, MinecraftServer server, UUID playerId) {
        return minecraft.getSingleplayerServer() == server && minecraft.player != null
            && minecraft.player.getUUID().equals(playerId);
    }

    private static void report(ServerPlayer player, String message, Exception exception) {
        LogUtils.getLogger().warn(message, exception);
        player.sendSystemMessage(Component.literal(message + " · " + exception.getMessage()));
    }

    static void publish(ServerLevel level, UUID player, UUID scope, LevelEntry entry, long seconds, long remaining,
                        int escaped, int preparationSeconds) {
        Status current = hud;
        if (current == null || !current.scope().equals(scope) || current.seconds() != seconds
            || current.remaining() != remaining || current.escaped() != escaped
            || current.preparationSeconds() != preparationSeconds) {
            hud = new Status(level.getServer(), level.dimension(), player, scope, entry.title(), seconds, remaining,
                escaped, preparationSeconds);
        }
    }

    static void clear(ServerLevel level, UUID scope) {
        Status current = hud;
        if (current != null && current.server() == level.getServer() && current.dimension().equals(level.dimension())
            && (scope == null || current.scope().equals(scope))) {
            hud = null;
        }
    }

    public static void drawHud(Minecraft minecraft, GuiGraphicsExtractor graphics) {
        Status current = hud;
        if (current == null || minecraft.getSingleplayerServer() != current.server() || minecraft.player == null
            || !minecraft.player.getUUID().equals(current.player())
            || !minecraft.player.level().dimension().equals(current.dimension())) {
            return;
        }
        long seconds = current.seconds();
        String clock = current.preparationSeconds() > 0 ? "До начала " + current.preparationSeconds() + " с"
            : String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
        String details = clock + "   ·   Мобов осталось " + current.remaining();
        String finish = "Дошли до финиша " + current.escaped() + " / " + ESCAPE_LIMIT;
        int maxWidth = graphics.guiWidth() - 16;
        String title = minecraft.font.plainSubstrByWidth(current.title(), maxWidth - 16);
        int textWidth = Math.max(minecraft.font.width(title), minecraft.font.width(details));
        int width = Math.min(maxWidth, Math.max(textWidth, minecraft.font.width(finish)) + 16);
        int left = (graphics.guiWidth() - width) / 2;
        graphics.fill(left, 6, left + width, 46, 0xe916191e);
        graphics.fill(left, 6, left + width, 8, 0xff42caea);
        graphics.centeredText(minecraft.font, title, graphics.guiWidth() / 2, 11, 0xffffffff);
        graphics.centeredText(minecraft.font, details, graphics.guiWidth() / 2, 22, 0xffb8e8f2);
        graphics.centeredText(minecraft.font, finish, graphics.guiWidth() / 2, 33,
            current.escaped() > 0 ? 0xffffb7a1 : 0xffb8e8f2);
    }

    record LevelEntry(String stage, String title, Double x, Double y) {}

    record Result(UUID scope, LevelEntry entry, boolean victory, boolean progressSaved) {}

    record Menu(List<LevelEntry> visible, Set<String> completed) {}

    private record Status(MinecraftServer server, ResourceKey<Level> dimension, UUID player, UUID scope, String title,
                          long seconds, long remaining, int escaped, int preparationSeconds) {}
}
