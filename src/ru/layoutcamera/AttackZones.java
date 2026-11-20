package ru.layoutcamera;

import com.google.gson.JsonElement;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import net.minecraft.world.entity.Mob;
import ru.layoutcamera.ZoneSelection.ZoneColor;

public final class AttackZones {
    private static final String FILTER_TAG = "layoutcamera_attack_zone_filter";
    private static final String ATTACK_TAG = "layoutcamera_attack_zone=";
    private static final String PLACEMENT_TAG = "layoutcamera_placement_zone=";

    private AttackZones() {}

    static Set<ZoneColor> read(JsonElement value) {
        if (value == null) {
            return null;
        }
        if (!value.isJsonArray()) {
            throw new IllegalArgumentException("attack_zones должен быть списком: blue, green, red, white.");
        }
        Set<ZoneColor> zones = EnumSet.noneOf(ZoneColor.class);
        for (JsonElement entry : value.getAsJsonArray()) {
            if (!entry.isJsonPrimitive() || !entry.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException("В attack_zones допустимы blue, green, red и white.");
            }
            try {
                zones.add(ZoneColor.valueOf(entry.getAsString().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Неизвестная зона в attack_zones: " + entry.getAsString(), exception);
            }
        }
        return Set.copyOf(zones);
    }

    static void assign(Mob mob, Set<ZoneColor> zones) {
        if (zones != null && zones.size() < ZoneColor.values().length) {
            mob.addTag(FILTER_TAG);
            zones.forEach(zone -> mob.addTag(ATTACK_TAG + zone.name()));
        }
    }

    static void markPlacement(Mob mob, ZoneColor zone) {
        if (zone != null) {
            mob.addTag(PLACEMENT_TAG + zone.name());
        }
    }

    public static boolean allows(Mob attacker, Mob defender) {
        if (!LocalLevels.sameScope(attacker, defender)) {
            return false;
        }
        var allowed = attacker.entityTags();
        if (!allowed.contains(FILTER_TAG)) {
            return true;
        }
        var placement = defender.entityTags();
        for (ZoneColor zone : ZoneColor.values()) {
            if (placement.contains(PLACEMENT_TAG + zone.name())) {
                return allowed.contains(ATTACK_TAG + zone.name());
            }
        }
        return false;
    }
}
