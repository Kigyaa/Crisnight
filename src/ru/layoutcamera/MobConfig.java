package ru.layoutcamera;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Properties;

record MobConfig(String id, String name, double attackSpeed, String attackType, double damage,
                 double defense, String mobType, double armor, double health) {
    static MobConfig load(Path projectDirectory, String fileName) {
        Path file = projectDirectory.resolve("config/layout-camera/mobs").resolve(fileName + ".properties");
        String resource = "/layoutcamera/mobs/" + file.getFileName();
        try (var input = Objects.requireNonNull(MobConfig.class.getResourceAsStream(resource), resource)) {
            byte[] template = input.readAllBytes();
            MobConfig defaults = read(new InputStreamReader(new java.io.ByteArrayInputStream(template), StandardCharsets.UTF_8));
            try {
                if (Files.notExists(file)) {
                    Files.createDirectories(file.getParent());
                    Files.write(file, template, java.nio.file.StandardOpenOption.CREATE_NEW);
                }
                try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    return read(reader);
                }
            } catch (IOException | IllegalArgumentException exception) {
                LogUtils.getLogger().warn("Could not load mob config {}, using defaults", file, exception);
                return defaults;
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read bundled mob config " + resource, exception);
        }
    }

    private static MobConfig read(java.io.Reader reader) throws IOException {
        Properties values = new Properties();
        values.load(reader);
        return new MobConfig(required(values, "id"), required(values, "name"),
            number(values, "attack_speed", 0), required(values, "attack_type"), number(values, "damage", 0),
            number(values, "defense", 0), required(values, "mob_type"), number(values, "armor", 0),
            number(values, "health", 1));
    }

    private static String required(Properties values, String key) {
        String value = values.getProperty(key, "").strip();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Missing " + key);
        }
        return value;
    }

    private static double number(Properties values, String key, double minimum) {
        double value = Double.parseDouble(required(values, key));
        if (!Double.isFinite(value) || value < minimum) {
            throw new IllegalArgumentException("Invalid " + key);
        }
        return value;
    }
}
