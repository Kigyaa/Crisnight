package ru.layoutcamera;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.world.entity.Mob;

final class AttackBlocks {
    private static final String LIMIT_TAG = "layoutcamera_attack_block=";
    private static final Map<Mob, List<WeakReference<Mob>>> held = new WeakHashMap<>();

    private AttackBlocks() {}

    static void assign(Mob mob, int limit) {
        mob.addTag(LIMIT_TAG + limit);
    }

    static synchronized boolean hasSpace(Mob defender) {
        var attackers = liveAttackers(defender);
        return (attackers == null ? 0 : attackers.size()) < limit(defender);
    }

    static synchronized boolean reserve(Mob defender, Mob attacker) {
        if (!defender.isAlive() || !attacker.isAlive() || attacker.level() != defender.level()) {
            return false;
        }
        if (holds(defender, attacker)) {
            return true;
        }
        if (!hasSpace(defender)) {
            return false;
        }
        held.computeIfAbsent(defender, ignored -> new ArrayList<>()).add(new WeakReference<>(attacker));
        return true;
    }

    static synchronized boolean holds(Mob defender, Mob attacker) {
        var attackers = liveAttackers(defender);
        return attackers != null && attackers.stream().anyMatch(reference -> reference.get() == attacker);
    }

    static synchronized void release(Mob defender, Mob attacker) {
        var attackers = held.get(defender);
        if (attackers != null) {
            attackers.removeIf(reference -> reference.get() == attacker);
            if (attackers.isEmpty()) {
                held.remove(defender);
            }
        }
    }

    private static List<WeakReference<Mob>> liveAttackers(Mob defender) {
        var attackers = held.get(defender);
        if (attackers != null) {
            attackers.removeIf(reference -> {
                Mob attacker = reference.get();
                return attacker == null || !attacker.isAlive() || attacker.level() != defender.level()
                    || !FactionCombat.canAttackAtHeight(attacker, defender);
            });
            if (attackers.isEmpty()) {
                held.remove(defender);
                return null;
            }
        }
        return attackers;
    }

    private static int limit(Mob defender) {
        for (String tag : defender.entityTags()) {
            if (tag.startsWith(LIMIT_TAG)) {
                try {
                    return Math.max(0, Integer.parseInt(tag.substring(LIMIT_TAG.length())));
                } catch (NumberFormatException ignored) {
                    // Old or malformed saved tags use the default.
                }
            }
        }
        return 1;
    }
}
