package org.isolatedareas.helphub.automation;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.isolatedareas.helphub.geo.GeoMath;

/**
 * Plans simulated cart trips for delivery requests whose stock is already reserved.
 *
 * <p>Jobs are placed most-urgent first. Each goes to the cart (with room left) where it adds
 * the least distance, weighed against that cart's resulting trip length, which keeps nearby
 * jobs on one cart and sends work heading the other way to an idle cart. A
 * trip visits its pickup points nearest-first, delivers in an order improved by 2-opt, and
 * returns to the service point closest to its last delivery. Optimal routing is NP-hard; this
 * is a heuristic. Travel time uses an estimated urban speed on straight-line distance scaled
 * by a road factor; the positions on the map are interpolated from this plan.
 */
public class DeliveryDispatchAlgorithm {
    static final double SPEED_METERS_PER_SECOND = 25_000.0 / 3600.0;
    static final double ROAD_FACTOR = 1.3;
    static final Duration PICKUP_DWELL = Duration.ofMinutes(5);
    static final Duration DROPOFF_DWELL = Duration.ofMinutes(3);
    private static final int TWO_OPT_MAX_PASSES = 50;
    /** Weight of a cart's total trip length next to the distance a job adds; spreads work across carts. */
    static final double BALANCE_WEIGHT = 0.3;

    public List<Trip> plan(List<Cart> carts, List<Job> jobs, List<Point> servicePoints, Instant start) {
        Map<Long, List<Job>> assigned = new LinkedHashMap<>();
        carts.forEach(cart -> assigned.put(cart.id(), new ArrayList<>()));
        Map<Long, Integer> load = new LinkedHashMap<>();
        List<Job> ordered = jobs.stream()
            .sorted(Comparator.comparingInt((Job job) -> urgencyRank(job.urgency())).thenComparingLong(Job::requestId))
            .toList();
        for (Job job : ordered) {
            Cart best = null;
            double bestScore = Double.MAX_VALUE;
            for (Cart cart : carts) {
                if (load.getOrDefault(cart.id(), 0) + job.quantity() > cart.capacity()) continue;
                List<Job> current = assigned.get(cart.id());
                double before = current.isEmpty() ? 0 : route(cart, current, servicePoints, false).length();
                List<Job> candidate = new ArrayList<>(current);
                candidate.add(job);
                double after = route(cart, candidate, servicePoints, false).length();
                // Mostly the distance this job adds (keeps a neighbourhood on one cart), plus a share
                // of the resulting trip so an idle cart takes work heading the other way.
                double score = (after - before) + BALANCE_WEIGHT * after;
                if (score < bestScore) {
                    bestScore = score;
                    best = cart;
                }
            }
            if (best == null) continue;
            assigned.get(best.id()).add(job);
            load.merge(best.id(), job.quantity(), Integer::sum);
        }

        List<Trip> trips = new ArrayList<>();
        for (Cart cart : carts) {
            List<Job> cartJobs = assigned.get(cart.id());
            if (!cartJobs.isEmpty()) trips.add(tripFor(cart, cartJobs, servicePoints, start));
        }
        return trips;
    }

    private Trip tripFor(Cart cart, List<Job> jobs, List<Point> servicePoints, Instant start) {
        Route route = route(cart, jobs, servicePoints, true);
        List<Stop> stops = new ArrayList<>();
        double lat = cart.latitude();
        double lon = cart.longitude();
        Instant clock = start;
        for (Point pickup : route.pickups()) {
            Instant arrive = clock.plus(travel(leg(lat, lon, pickup.latitude(), pickup.longitude())));
            clock = arrive.plus(PICKUP_DWELL);
            stops.add(new Stop(StopType.PICKUP, pickup.id(), null, null, pickup.latitude(), pickup.longitude(), arrive, clock));
            lat = pickup.latitude();
            lon = pickup.longitude();
        }
        for (Job drop : route.drops()) {
            Instant arrive = clock.plus(travel(leg(lat, lon, drop.latitude(), drop.longitude())));
            // Never hand over before the slot the resident booked; the cart waits instead.
            if (drop.notBefore() != null && arrive.isBefore(drop.notBefore())) arrive = drop.notBefore();
            clock = arrive.plus(DROPOFF_DWELL);
            stops.add(new Stop(StopType.DROPOFF, null, drop.requestId(), drop.reservationId(),
                drop.latitude(), drop.longitude(), arrive, clock));
            lat = drop.latitude();
            lon = drop.longitude();
        }
        if (route.home() != null) {
            Point home = route.home();
            Instant arrive = clock.plus(travel(leg(lat, lon, home.latitude(), home.longitude())));
            stops.add(new Stop(StopType.RETURN, home.id(), null, null, home.latitude(), home.longitude(), arrive, arrive));
        }
        return new Trip(cart.id(), stops, Math.round(route.length()));
    }

    /** Pickups nearest-first, deliveries nearest-first (optionally improved by 2-opt), then home. */
    Route route(Cart cart, List<Job> jobs, List<Point> servicePoints, boolean improve) {
        double lat = cart.latitude();
        double lon = cart.longitude();
        double length = 0;
        Map<Long, Point> pickupsById = new LinkedHashMap<>();
        jobs.forEach(job -> pickupsById.putIfAbsent(job.pickup().id(), job.pickup()));
        List<Point> remainingPickups = new ArrayList<>(pickupsById.values());
        List<Point> pickups = new ArrayList<>();
        while (!remainingPickups.isEmpty()) {
            Point next = nearest(lat, lon, remainingPickups, Point::latitude, Point::longitude);
            remainingPickups.remove(next);
            pickups.add(next);
            length += leg(lat, lon, next.latitude(), next.longitude());
            lat = next.latitude();
            lon = next.longitude();
        }
        List<Job> drops = new ArrayList<>();
        List<Job> remainingDrops = new ArrayList<>(jobs);
        double dropLat = lat;
        double dropLon = lon;
        while (!remainingDrops.isEmpty()) {
            Job next = nearest(dropLat, dropLon, remainingDrops, Job::latitude, Job::longitude);
            remainingDrops.remove(next);
            drops.add(next);
            dropLat = next.latitude();
            dropLon = next.longitude();
        }
        if (improve) drops = twoOpt(lat, lon, drops, servicePoints);
        length += dropsLength(lat, lon, drops, servicePoints);
        Job last = drops.isEmpty() ? null : drops.getLast();
        Point home = last == null ? null : nearest(last.latitude(), last.longitude(), servicePoints, Point::latitude, Point::longitude);
        return new Route(pickups, drops, home, length);
    }

    /** Reverses delivery segments while that shortens the path, including the drive home. */
    static List<Job> twoOpt(double startLat, double startLon, List<Job> input, List<Point> servicePoints) {
        List<Job> route = new ArrayList<>(input);
        double best = dropsLength(startLat, startLon, route, servicePoints);
        boolean improved = true;
        for (int pass = 0; improved && pass < TWO_OPT_MAX_PASSES; pass++) {
            improved = false;
            for (int i = 0; i < route.size() - 1; i++) {
                for (int k = i + 1; k < route.size(); k++) {
                    Collections.reverse(route.subList(i, k + 1));
                    double length = dropsLength(startLat, startLon, route, servicePoints);
                    if (length + 0.5 < best) {
                        best = length;
                        improved = true;
                    } else {
                        Collections.reverse(route.subList(i, k + 1));
                    }
                }
            }
        }
        return route;
    }

    static double dropsLength(double startLat, double startLon, List<Job> drops, List<Point> servicePoints) {
        double length = 0;
        double lat = startLat;
        double lon = startLon;
        for (Job drop : drops) {
            length += leg(lat, lon, drop.latitude(), drop.longitude());
            lat = drop.latitude();
            lon = drop.longitude();
        }
        if (!drops.isEmpty() && !servicePoints.isEmpty()) {
            Point home = nearest(lat, lon, servicePoints, Point::latitude, Point::longitude);
            length += leg(lat, lon, home.latitude(), home.longitude());
        }
        return length;
    }

    private static double leg(double lat1, double lon1, double lat2, double lon2) {
        return GeoMath.distanceMeters(lat1, lon1, lat2, lon2) * ROAD_FACTOR;
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

    record Route(List<Point> pickups, List<Job> drops, Point home, double length) {
    }
    public record Cart(long id, int capacity, double latitude, double longitude) {
    }
    public record Point(long id, double latitude, double longitude) {
    }
    public record Job(long requestId, long reservationId, int quantity, String urgency, Point pickup,
                      double latitude, double longitude, Instant notBefore) {
        /** A delivery wanted as soon as possible. */
        public Job(long requestId, long reservationId, int quantity, String urgency, Point pickup,
                   double latitude, double longitude) {
            this(requestId, reservationId, quantity, urgency, pickup, latitude, longitude, null);
        }
    }
    public record Stop(StopType type, Long servicePointId, Long requestId, Long reservationId,
                       double latitude, double longitude, Instant arriveAt, Instant departAt) {
    }
    public record Trip(long cartId, List<Stop> stops, long distanceMeters) {
    }
}
