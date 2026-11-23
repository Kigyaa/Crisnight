package ru.layoutcamera;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.phys.Vec3;
import ru.layoutcamera.mixin.ParticleAccessor;

public final class RouteParticles {
    private static final int MAX_PARTICLES = 128;
    static final int MAX_STAGE_PARTICLES = 2048;
    private static final Map<UUID, Map<UUID, List<Vec3>>> previews = new ConcurrentHashMap<>();
    private static final Map<UUID, Trail> trails = new HashMap<>();
    private static ClientLevel clientLevel;
    private static UUID clientScope;

    private RouteParticles() {}

    static void show(UUID scope, Map<UUID, List<Vec3>> routes) {
        if (!routes.isEmpty()) {
            previews.put(scope, Map.copyOf(routes));
        }
    }

    static Map<UUID, List<Vec3>> snapshot(UUID scope) {
        return scope == null ? Map.of() : previews.getOrDefault(scope, Map.of());
    }

    static boolean hide(UUID scope, UUID zone) {
        if (scope == null || zone == null || !snapshot(scope).containsKey(zone)) {
            return false;
        }
        previews.computeIfPresent(scope, (id, previous) -> {
            var remaining = new HashMap<>(previous);
            remaining.remove(zone);
            return remaining.isEmpty() ? null : Map.copyOf(remaining);
        });
        return true;
    }

    static void clear(UUID scope) {
        if (scope != null) {
            previews.remove(scope);
        }
    }

    public static void tick(Minecraft minecraft) {
        if (minecraft.level == null || minecraft.player == null) {
            reset();
            return;
        }
        var server = minecraft.getSingleplayerServer();
        var layout = server == null ? StagePlanning.clientLayout(minecraft)
            : StageCopies.layout(server, minecraft.level.dimension(), minecraft.player.getUUID());
        UUID scope = layout == null ? null : layout.id();
        if (clientLevel != minecraft.level || !Objects.equals(clientScope, scope)) {
            reset();
            clientLevel = minecraft.level;
            clientScope = scope;
        }
        var desired = server == null ? StagePlanning.clientRoutes(minecraft) : snapshot(scope);
        trails.entrySet().removeIf(entry -> {
            Trail trail = entry.getValue();
            if (trail.points().equals(desired.get(entry.getKey())) && trail.particles().stream().allMatch(Particle::isAlive)) {
                return false;
            }
            trail.remove();
            return true;
        });
        desired.forEach((zone, points) -> trails.computeIfAbsent(zone, id -> createTrail(minecraft, points)));
    }

    public static void reset() {
        trails.values().forEach(Trail::remove);
        trails.clear();
        clientLevel = null;
        clientScope = null;
    }

    private static Trail createTrail(Minecraft minecraft, List<Vec3> points) {
        List<Particle> particles = new ArrayList<>();
        for (Vec3 point : points) {
            Particle particle = minecraft.particleEngine.createParticle(ParticleTypes.END_ROD,
                point.x, point.y, point.z, 0, 0, 0);
            if (particle != null) {
                ((ParticleAccessor) particle).layoutcamera$setGravity(0);
                particle.setLifetime(Integer.MAX_VALUE);
                particles.add(particle);
            }
        }
        return new Trail(points, particles);
    }

    private record Trail(List<Vec3> points, List<Particle> particles) {
        void remove() {
            particles.forEach(Particle::remove);
        }
    }

    static List<Vec3> sample(List<Vec3> path) {
        if (path.isEmpty()) {
            return List.of();
        }
        double length = 0;
        for (int i = 1; i < path.size(); i++) {
            length += path.get(i - 1).distanceTo(path.get(i));
        }
        if (length == 0) {
            return List.of(path.getFirst().add(0, 0.18, 0));
        }
        int intervals = Math.clamp((int) Math.ceil(length / 0.35), 1, MAX_PARTICLES - 1);
        List<Vec3> points = new ArrayList<>(intervals + 1);
        points.add(path.getFirst().add(0, 0.18, 0));
        int index = 1;
        double passed = 0;
        for (int segment = 1; segment < path.size() && index < intervals; segment++) {
            Vec3 from = path.get(segment - 1);
            Vec3 to = path.get(segment);
            double distance = from.distanceTo(to);
            if (distance > 0) {
                while (index < intervals && length * index / intervals <= passed + distance) {
                    points.add(from.lerp(to, (length * index / intervals - passed) / distance).add(0, 0.18, 0));
                    index++;
                }
                passed += distance;
            }
        }
        points.add(path.getLast().add(0, 0.18, 0));
        return List.copyOf(points);
    }

}
