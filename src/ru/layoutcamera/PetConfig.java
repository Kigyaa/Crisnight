package ru.layoutcamera;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.Identifier;

record PetConfig(Identifier id, Identifier model, Identifier summonItem, JsonObject values) {
    static final Identifier PLANNING_CHICKEN = Identifier.parse("layoutcamera:planning_chicken");
    static final Path PROJECT_DIRECTORY = Path.of(System.getProperty("user.home"), "Desktop", "Cristnights");
    private static final String FILE = "config/layout-camera/mobs.json";

    static Identifier petId(String value) {
        return Identifier.tryParse(value.contains(":") ? value : "layoutcamera:" + value);
    }

    static synchronized Map<Identifier, PetConfig> load() throws IOException {
        Path file = PROJECT_DIRECTORY.resolve(FILE);
        if (Files.notExists(file)) {
            Files.createDirectories(file.getParent());
            MobConfig legacy = MobConfig.load(PROJECT_DIRECTORY, "planning_chicken");
            JsonObject definitions = new JsonObject();
            definitions.add(PLANNING_CHICKEN.toString(), planningDefaults(legacy));
            String json = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(definitions);
            Files.writeString(file, json + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        }
        try (JsonReader reader = new JsonReader(Files.newBufferedReader(file, StandardCharsets.UTF_8))) {
            Map<Identifier, PetConfig> definitions = new LinkedHashMap<>();
            reader.beginObject();
            while (reader.hasNext()) {
                String key = reader.nextName();
                Identifier id = petId(key);
                if (id == null || definitions.containsKey(id)) {
                    throw new IOException("Invalid or duplicate mob ID: " + key);
                }
                JsonObject values = JsonParser.parseReader(reader).getAsJsonObject();
                PetConfig definition = new PetConfig(id, identifier(values, "model", null),
                    identifier(values, "summon_item", "minecraft:clay_ball"), values);
                definition.text("name", "");
                definition.projectile();
                definition.text("mob_type", "");
                definition.canMove();
                definition.movementSpeed();
                definition.canPlace();
                definition.placementZone();
                definition.faction();
                definition.attackZones();
                definition.attackGrid();
                definition.attackKnockback();
                definition.attackBlock();
                definition.number("attack_speed", 0);
                definition.number("damage", 0);
                definition.number("defense", 0);
                definition.number("armor", 0);
                definition.number("health", 1);
                definitions.put(id, definition);
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw new IOException("Unexpected content after mob definitions");
            }
            return definitions;
        } catch (RuntimeException exception) {
            throw new IOException("Invalid mob config: " + exception.getMessage(), exception);
        }
    }

    static MobConfig planningChicken() {
        try {
            PetConfig pet = load().get(PLANNING_CHICKEN);
            if (pet != null) {
                return new MobConfig(pet.id().toString(), pet.text("name", "Курица планировки"),
                    pet.numberOr("attack_speed", 0, 0), pet.text("attack_type", "none"),
                    pet.numberOr("damage", 0, 0), pet.numberOr("defense", 0, 0),
                    pet.text("mob_type", "passive"), pet.numberOr("armor", 0, 0), pet.numberOr("health", 1, 4));
            }
        } catch (IOException | RuntimeException exception) {
            LogUtils.getLogger().warn("Could not load shared planning chicken config", exception);
        }
        return MobConfig.load(PROJECT_DIRECTORY, "planning_chicken");
    }

    String text(String key, String fallback) {
        if (!values.has(key)) {
            return fallback;
        }
        var value = values.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(id + ": invalid " + key);
        }
        return value.getAsString();
    }

    boolean canMove() {
        return flag("can_move");
    }

    Double movementSpeed() {
        var value = values.get("movement_speed");
        if (value == null) {
            return null;
        }
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
            double speed = value.getAsDouble();
            if (Double.isFinite(speed) && speed >= 0 && speed <= 1024) {
                return speed;
            }
        }
        throw new IllegalArgumentException(id + ": movement_speed must be a number from 0 to 1024");
    }

    boolean canPlace() {
        return flag("can_place");
    }

    FactionCombat.Side faction() {
        String value = text("faction", "");
        if (value.isEmpty() && !values.has("faction")) {
            return null;
        }
        return switch (value) {
            case "peaceful" -> FactionCombat.Side.PEACEFUL;
            case "hostile" -> FactionCombat.Side.HOSTILE;
            default -> throw new IllegalArgumentException(id + ": faction must be peaceful or hostile");
        };
    }

    Set<ZoneSelection.ZoneColor> attackZones() {
        return AttackZones.read(values.get("attack_zones"));
    }

    AttackGrid attackGrid() {
        try {
            return AttackGrid.read(values.get("attack_grid"));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(id + " · " + exception.getMessage(), exception);
        }
    }

    ProjectileAttack.Ammo projectile() {
        if (!"ranged".equals(text("attack_type", ""))) {
            if (values.has("projectile")) {
                throw new IllegalArgumentException(id + " · projectile используется с attack_type: ranged.");
            }
            return null;
        }
        try {
            return ProjectileAttack.Ammo.valueOf(text("projectile", "arrow").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(id + " · projectile должен быть arrow, snowball или egg.", exception);
        }
    }

    int attackBlock() {
        var value = values.get("attack_block");
        if (value == null) {
            return 1;
        }
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
            try {
                int limit = value.getAsBigDecimal().intValueExact();
                if (limit >= 0) {
                    return limit;
                }
            } catch (ArithmeticException ignored) {
                // Reject non-integer and out-of-range values.
            }
        }
        throw new IllegalArgumentException(id + ": attack_block must be a non-negative integer");
    }

    double attackKnockback() {
        var value = values.get("attack_knockback");
        if (value == null) {
            return 0;
        }
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
            double strength = value.getAsDouble();
            if (AttackKnockback.valid(strength)) {
                return strength;
            }
        }
        throw new IllegalArgumentException(id + " · attack_knockback должен быть числом от 0 до 5.");
    }

    ZoneSelection.ZoneColor placementZone() {
        try {
            return ZoneSelection.ZoneColor.valueOf(text("placement_zone", "blue").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(id + ": placement_zone must be blue, green, red or white", exception);
        }
    }

    private boolean flag(String key) {
        if (!values.has(key)) {
            return true;
        }
        var value = values.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException(id + ": " + key + " must be true or false");
        }
        return value.getAsBoolean();
    }

    Double number(String key, double minimum) {
        if (!values.has(key)) {
            return null;
        }
        double value = values.get(key).getAsDouble();
        if (!Double.isFinite(value) || value < minimum) {
            throw new IllegalArgumentException(id + ": invalid " + key);
        }
        return value;
    }

    private double numberOr(String key, double minimum, double fallback) {
        Double value = number(key, minimum);
        return value == null ? fallback : value;
    }

    private static Identifier identifier(JsonObject values, String key, String fallback) {
        String value = values.has(key) ? values.get(key).getAsString() : fallback;
        Identifier id = value == null ? null : Identifier.tryParse(value);
        if (id == null) {
            throw new IllegalArgumentException("Invalid or missing " + key);
        }
        return id;
    }

    private static JsonObject planningDefaults(MobConfig legacy) {
        JsonObject values = new JsonObject();
        values.addProperty("model", "minecraft:chicken");
        values.addProperty("summon_item", "minecraft:clay_ball");
        values.addProperty("can_move", true);
        values.addProperty("can_place", true);
        values.addProperty("faction", "peaceful");
        values.addProperty("attack_block", 1);
        values.addProperty("attack_knockback", 0);
        values.addProperty("placement_zone", "blue");
        values.addProperty("name", legacy.name());
        values.addProperty("attack_speed", legacy.attackSpeed());
        values.addProperty("attack_type", legacy.attackType());
        values.addProperty("damage", legacy.damage());
        values.addProperty("defense", legacy.defense());
        values.addProperty("mob_type", legacy.mobType());
        values.addProperty("armor", legacy.armor());
        values.addProperty("health", legacy.health());
        return values;
    }
}
