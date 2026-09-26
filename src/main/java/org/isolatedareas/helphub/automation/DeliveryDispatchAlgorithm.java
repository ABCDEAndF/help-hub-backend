package org.isolatedareas.helphub.automation;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.isolatedareas.helphub.geo.GeoMath;

/**
 * Plans simulated cart trips for delivery requests whose stock is already reserved.
 *
 * <p>Each job goes to the available cart closest to the service point holding its stock
 * (most urgent jobs first, respecting capacity). A cart's trip visits its pickup points
 * nearest-first, then delivers nearest-first, then returns to the service point closest to
 * its last delivery. Travel time uses an estimated urban speed on straight-line distance
 * scaled by a road factor; the positions shown on the map are interpolated from this plan.
 */
public class DeliveryDispatchAlgorithm {
    static final double SPEED_METERS_PER_SECOND = 25_000.0 / 3600.0;
    static final double ROAD_FACTOR = 1.3;
    static final Duration PICKUP_DWELL = Duration.ofMinutes(5);
    static final Duration DROPOFF_DWELL = Duration.ofMinutes(3);

    public List<Trip> plan(List<Cart> carts, List<Job> jobs, List<Point> servicePoints, Instant start) {
        Map<Long, List<Job>> assigned = new LinkedHashMap<>();
        Map<Long, Integer> load = new LinkedHashMap<>();
        List<Job> ordered = jobs.stream()
            .sorted(Comparator.comparingInt((Job job) -> urgencyRank(job.urgency())).thenComparingLong(Job::requestId))
            .toList();
        for (Job job : ordered) {
            Cart best = null;
            double bestDistance = Double.MAX_VALUE;
            for (Cart cart : carts) {
                if (load.getOrDefault(cart.id(), 0) + job.quantity() > cart.capacity()) continue;
                double distance = GeoMath.distanceMeters(cart.latitude(), cart.longitude(),
                    job.pickup().latitude(), job.pickup().longitude());
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = cart;
                }
            }
            if (best == null) continue;
            assigned.computeIfAbsent(best.id(), id -> new ArrayList<>()).add(job);
            load.merge(best.id(), job.quantity(), Integer::sum);
        }

        List<Trip> trips = new ArrayList<>();
        for (Cart cart : carts) {
            List<Job> cartJobs = assigned.get(cart.id());
            if (cartJobs == null) continue;
            trips.add(tripFor(cart, cartJobs, servicePoints, start));
        }
        return trips;
    }

    private Trip tripFor(Cart cart, List<Job> jobs, List<Point> servicePoints, Instant start) {
        List<Stop> stops = new ArrayList<>();
        double lat = cart.latitude();
        double lon = cart.longitude();
        Instant clock = start;
        double distance = 0;

        Map<Long, Point> pickups = new LinkedHashMap<>();
        jobs.forEach(job -> pickups.putIfAbsent(job.pickup().id(), job.pickup()));
        List<Point> remainingPickups = new ArrayList<>(pickups.values());
        while (!remainingPickups.isEmpty()) {
            Point next = nearest(lat, lon, remainingPickups, Point::latitude, Point::longitude);
            remainingPickups.remove(next);
            double leg = GeoMath.distanceMeters(lat, lon, next.latitude(), next.longitude()) * ROAD_FACTOR;
            distance += leg;
            Instant arrive = clock.plus(travel(leg));
            clock = arrive.plus(PICKUP_DWELL);
            stops.add(new Stop(StopType.PICKUP, next.id(), null, null, next.latitude(), next.longitude(), arrive, clock));
            lat = next.latitude();
            lon = next.longitude();
        }

        List<Job> remainingDrops = new ArrayList<>(jobs);
        while (!remainingDrops.isEmpty()) {
            Job next = nearest(lat, lon, remainingDrops, Job::latitude, Job::longitude);
            remainingDrops.remove(next);
            double leg = GeoMath.distanceMeters(lat, lon, next.latitude(), next.longitude()) * ROAD_FACTOR;
            distance += leg;
            Instant arrive = clock.plus(travel(leg));
            clock = arrive.plus(DROPOFF_DWELL);
            stops.add(new Stop(StopType.DROPOFF, null, next.requestId(), next.reservationId(),
                next.latitude(), next.longitude(), arrive, clock));
            lat = next.latitude();
            lon = next.longitude();
        }

        Point home = nearest(lat, lon, servicePoints, Point::latitude, Point::longitude);
        if (home != null) {
            double leg = GeoMath.distanceMeters(lat, lon, home.latitude(), home.longitude()) * ROAD_FACTOR;
            distance += leg;
            Instant arrive = clock.plus(travel(leg));
            stops.add(new Stop(StopType.RETURN, home.id(), null, null, home.latitude(), home.longitude(), arrive, arrive));
        }
        return new Trip(cart.id(), stops, Math.round(distance));
    }

    static Duration travel(double roadMeters) {
        return Duration.ofSeconds(Math.round(roadMeters / SPEED_METERS_PER_SECOND));
    }

    private static <T> T nearest(double lat, double lon, List<T> options,
                                 java.util.function.ToDoubleFunction<T> latitude,
                                 java.util.function.ToDoubleFunction<T> longitude) {
        return options.stream().min(Comparator.comparingDouble(option ->
            GeoMath.distanceMeters(lat, lon, latitude.applyAsDouble(option), longitude.applyAsDouble(option))))
            .orElse(null);
    }

    private static int urgencyRank(String urgency) {
        return switch (urgency) {
            case "CRITICAL" -> 0;
            case "HIGH" -> 1;
            case "NORMAL" -> 2;
            default -> 3;
        };
    }

    public enum StopType { PICKUP, DROPOFF, RETURN }

    public record Cart(long id, int capacity, double latitude, double longitude) {
    }
    public record Point(long id, double latitude, double longitude) {
    }
    public record Job(long requestId, long reservationId, int quantity, String urgency, Point pickup,
                      double latitude, double longitude) {
    }
    public record Stop(StopType type, Long servicePointId, Long requestId, Long reservationId,
                       double latitude, double longitude, Instant arriveAt, Instant departAt) {
    }
    public record Trip(long cartId, List<Stop> stops, long distanceMeters) {
    }
}
