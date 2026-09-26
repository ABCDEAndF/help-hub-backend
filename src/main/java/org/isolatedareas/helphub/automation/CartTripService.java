package org.isolatedareas.helphub.automation;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.isolatedareas.helphub.domain.RequestStatus;
import org.isolatedareas.helphub.geo.GeoMath;
import org.isolatedareas.helphub.inventory.ReservationService;
import org.isolatedareas.helphub.requests.RequestService;
import org.isolatedareas.helphub.requests.SupplyRequestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns reserved delivery requests into simulated cart trips and plays them out: carts
 * collect stock at service points, deliver, and return. There are no drivers, so reaching a
 * delivery stop completes the handover (the resident is notified), as the project owner chose.
 */
@Service
public class CartTripService {
    private static final Logger log = LoggerFactory.getLogger(CartTripService.class);
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of("Asia/Shanghai"));
    /** A reservation this close to expiry is not worth starting a trip for. */
    private static final Duration MIN_REMAINING_HOLD = Duration.ofHours(2);

    private final JdbcClient jdbc;
    private final RequestService requestService;
    private final SupplyRequestRepository requests;
    private final ReservationService reservations;
    private final SystemActor system;
    private final DeliveryDispatchAlgorithm algorithm = new DeliveryDispatchAlgorithm();

    public CartTripService(JdbcClient jdbc, RequestService requestService, SupplyRequestRepository requests,
                           ReservationService reservations, SystemActor system) {
        this.jdbc = jdbc;
        this.requestService = requestService;
        this.requests = requests;
        this.reservations = reservations;
        this.system = system;
    }

    /** True when an idle cart and a reserved, approved delivery request are both waiting. */
    public boolean dispatchNeeded() {
        return jdbc.sql("SELECT COUNT(*) FROM mobile_carts WHERE status='AVAILABLE' AND latitude IS NOT NULL")
            .query(Integer.class).single() > 0 && !pendingJobs(false).isEmpty();
    }

    /** Plans and starts trips for every dispatchable delivery; called by the RabbitMQ route consumer. */
    @Transactional
    public PlanOutcome planTrips(Long routePlanId) {
        List<DeliveryDispatchAlgorithm.Cart> carts = jdbc.sql("""
                SELECT id, capacity_units, latitude, longitude FROM mobile_carts
                WHERE status='AVAILABLE' AND latitude IS NOT NULL AND longitude IS NOT NULL ORDER BY id FOR UPDATE
                """)
            .query((rs, n) -> new DeliveryDispatchAlgorithm.Cart(rs.getLong("id"), rs.getInt("capacity_units"),
                rs.getDouble("latitude"), rs.getDouble("longitude"))).list();
        List<DeliveryDispatchAlgorithm.Job> jobs = pendingJobs(true);
        if (carts.isEmpty() || jobs.isEmpty()) return new PlanOutcome(List.of(), 0, 0, jobs.stream().map(DeliveryDispatchAlgorithm.Job::requestId).toList());
        List<DeliveryDispatchAlgorithm.Point> points = jdbc.sql(
                "SELECT id, latitude, longitude FROM service_points WHERE status='ACTIVE' ORDER BY id")
            .query((rs, n) -> new DeliveryDispatchAlgorithm.Point(rs.getLong("id"), rs.getDouble("latitude"),
                rs.getDouble("longitude"))).list();

        Instant start = Instant.now();
        List<DeliveryDispatchAlgorithm.Trip> trips = algorithm.plan(carts, jobs, points, start);
        Map<Long, DeliveryDispatchAlgorithm.Cart> cartById = new HashMap<>();
        carts.forEach(cart -> cartById.put(cart.id(), cart));
        long actor = system.id();
        long total = 0;
        List<Long> assigned = new ArrayList<>();
        for (DeliveryDispatchAlgorithm.Trip trip : trips) {
            DeliveryDispatchAlgorithm.Cart cart = cartById.get(trip.cartId());
            long tripId = insertTrip(trip, cart, routePlanId, start);
            String cartName = jdbc.sql("SELECT name FROM mobile_carts WHERE id=:id").param("id", trip.cartId())
                .query(String.class).single();
            int order = 1;
            for (DeliveryDispatchAlgorithm.Stop stop : trip.stops()) {
                insertStop(tripId, order, stop);
                if (stop.type() == DeliveryDispatchAlgorithm.StopType.DROPOFF) {
                    if (routePlanId != null) insertRouteStop(routePlanId, trip.cartId(), order, stop);
                    requestService.transition(stop.requestId(), actor,
                        new RequestService.TransitionRequest(RequestStatus.SCHEDULED, null, trip.cartId()), true);
                    requests.recordDecision(stop.requestId(), cartName + "已出发取货，预计 "
                        + CLOCK.format(stop.arriveAt()) + " 送达，可在地图查看车辆位置。");
                    assigned.add(stop.requestId());
                }
                order++;
            }
            jdbc.sql("UPDATE mobile_carts SET status='IN_SERVICE', version=version+1 WHERE id=:id")
                .param("id", trip.cartId()).update();
            total += trip.distanceMeters();
        }
        long baseline = jobs.stream().filter(job -> assigned.contains(job.requestId()))
            .mapToLong(job -> Math.round(2 * DeliveryDispatchAlgorithm.ROAD_FACTOR * GeoMath.distanceMeters(
                job.pickup().latitude(), job.pickup().longitude(), job.latitude(), job.longitude())))
            .sum();
        List<Long> unassigned = jobs.stream().map(DeliveryDispatchAlgorithm.Job::requestId)
            .filter(id -> !assigned.contains(id)).toList();
        return new PlanOutcome(assigned, total, baseline, unassigned);
    }

    /** Plays out every active trip up to now. Runs on every instance; row locks keep it single-writer. */
    @Scheduled(fixedDelay = 15_000, initialDelay = 20_000)
    @Transactional
    public void advance() {
        List<Long> trips = jdbc.sql("SELECT id FROM cart_trips WHERE status='ACTIVE' ORDER BY id FOR UPDATE")
            .query(Long.class).list();
        for (long tripId : trips) advanceTrip(tripId, Instant.now());
    }

    void advanceTrip(long tripId, Instant now) {
        long actor = system.id();
        long cartId = jdbc.sql("SELECT cart_id FROM cart_trips WHERE id=:id").param("id", tripId).query(Long.class).single();
        List<DueStop> due = jdbc.sql("""
                SELECT id, stop_type, request_id, reservation_id, latitude, longitude FROM cart_trip_stops
                WHERE trip_id=:trip AND status='PENDING' AND arrive_at <= :now ORDER BY stop_order
                """).param("trip", tripId).param("now", Timestamp.from(now))
            .query((rs, n) -> new DueStop(rs.getLong("id"), rs.getString("stop_type"),
                (Long) rs.getObject("request_id", Long.class), (Long) rs.getObject("reservation_id", Long.class),
                rs.getBigDecimal("latitude"), rs.getBigDecimal("longitude"))).list();
        for (DueStop stop : due) {
            if ("DROPOFF".equals(stop.type())) deliver(actor, stop);
            jdbc.sql("UPDATE cart_trip_stops SET status='DONE', completed_at=:now WHERE id=:id")
                .param("now", Timestamp.from(now)).param("id", stop.id()).update();
            jdbc.sql("""
                    UPDATE mobile_carts SET latitude=:lat, longitude=:lon, last_location_at=:now, version=version+1
                    WHERE id=:id
                    """).param("lat", stop.latitude()).param("lon", stop.longitude())
                .param("now", Timestamp.from(now)).param("id", cartId).update();
        }
        int pending = jdbc.sql("SELECT COUNT(*) FROM cart_trip_stops WHERE trip_id=:trip AND status='PENDING'")
            .param("trip", tripId).query(Integer.class).single();
        if (pending == 0) {
            jdbc.sql("UPDATE cart_trips SET status='COMPLETED', completed_at=:now WHERE id=:id")
                .param("now", Timestamp.from(now)).param("id", tripId).update();
            jdbc.sql("UPDATE mobile_carts SET status='AVAILABLE', version=version+1 WHERE id=:id")
                .param("id", cartId).update();
        }
    }

    private void deliver(long actor, DueStop stop) {
        boolean handedOver = stop.reservationId() != null && reservations.collectOnDelivery(actor, stop.reservationId());
        if (!handedOver) {
            log.warn("Delivery stop {} reached but reservation {} was no longer collectible", stop.id(), stop.reservationId());
            requests.recordDecision(stop.requestId(), "补给车已到达，但该预约已失效，未能交付；请重新提交需求。");
            return;
        }
        var request = requestService.get(stop.requestId());
        if (request.status() == RequestStatus.SCHEDULED) {
            requestService.transition(stop.requestId(), actor,
                new RequestService.TransitionRequest(RequestStatus.FULFILLED, null, null), true);
        }
        requests.recordDecision(stop.requestId(), "补给车已于 " + CLOCK.format(Instant.now()) + " 送达，本次服务已完成。");
    }

    /** Current position of every cart that is on a trip, keyed by cart id. */
    public Map<Long, CartMotion> motions(Instant now) {
        Map<Long, CartMotion> motions = new LinkedHashMap<>();
        for (TripRow trip : activeTrips()) motions.put(trip.cartId(), motion(trip, now, null));
        return motions;
    }

    /** What a resident may see about the cart delivering their own request. */
    public Optional<Tracking> tracking(long requestId, long residentId, Instant now) {
        Optional<Long> tripId = jdbc.sql("""
                SELECT t.id FROM cart_trip_stops s
                JOIN cart_trips t ON t.id = s.trip_id
                JOIN supply_requests r ON r.id = s.request_id
                WHERE s.request_id=:request AND r.resident_id=:resident AND s.stop_type='DROPOFF'
                ORDER BY t.id DESC LIMIT 1
                """).param("request", requestId).param("resident", residentId).query(Long.class).optional();
        if (tripId.isEmpty()) return Optional.empty();
        TripRow trip = trip(tripId.get());
        CartMotion motion = motion(trip, now, requestId);
        TripStopRow own = trip.stops().stream().filter(stop -> Long.valueOf(requestId).equals(stop.requestId()))
            .findFirst().orElseThrow();
        return Optional.of(new Tracking(trip.cartId(), trip.cartName(), motion.latitude(), motion.longitude(),
            motion.statusText(), own.arriveAt(), "DONE".equals(own.status()), own.latitude(), own.longitude(),
            motion.path()));
    }

    /** Every active trip with all stops, for operators. */
    public List<AdminTrip> adminTrips(Instant now) {
        List<AdminTrip> result = new ArrayList<>();
        for (TripRow trip : activeTrips()) {
            CartMotion motion = motion(trip, now, -1L);
            result.add(new AdminTrip(trip.id(), trip.cartId(), trip.cartName(), motion.latitude(), motion.longitude(),
                motion.statusText(), trip.distanceMeters(), trip.stops().stream().map(stop -> new AdminStop(
                    stop.type(), stop.label(), stop.requestId(), stop.latitude(), stop.longitude(), stop.arriveAt(),
                    stop.status())).toList()));
        }
        return result;
    }

    /**
     * pathFor: null = public view (only service points on the path), -1 = operator (everything),
     * otherwise the resident's own request id (their own stop, never other residents' addresses).
     */
    private CartMotion motion(TripRow trip, Instant now, Long pathFor) {
        List<TripProgress.TimedStop> timed = trip.stops().stream()
            .map(stop -> new TripProgress.TimedStop(stop.latitude(), stop.longitude(), stop.arriveAt(), stop.departAt()))
            .toList();
        TripProgress.Position position = TripProgress.at(trip.startLatitude(), trip.startLongitude(), trip.startedAt(), timed, now);
        String text;
        if (position.nextStopIndex() >= trip.stops().size()) {
            text = "已完成本次配送";
        } else {
            TripStopRow next = trip.stops().get(position.nextStopIndex());
            text = switch (next.type()) {
                case "PICKUP" -> position.atStop() ? "正在" + next.label() + "装货" : "前往" + next.label() + "取货";
                case "DROPOFF" -> position.atStop() ? "正在交付物资" : "配送途中";
                default -> "返回" + next.label();
            };
        }
        List<double[]> path = new ArrayList<>();
        path.add(new double[] {position.latitude(), position.longitude()});
        for (int index = position.nextStopIndex(); index < trip.stops().size(); index++) {
            TripStopRow stop = trip.stops().get(index);
            boolean visible = pathFor != null && pathFor == -1L
                || !"DROPOFF".equals(stop.type())
                || pathFor != null && pathFor.equals(stop.requestId());
            if (!visible) continue;
            path.add(new double[] {stop.latitude(), stop.longitude()});
            if (pathFor != null && pathFor != -1L && pathFor.equals(stop.requestId())) break;
        }
        return new CartMotion(position.latitude(), position.longitude(), text, path);
    }

    private List<TripRow> activeTrips() {
        return jdbc.sql("SELECT id FROM cart_trips WHERE status='ACTIVE' ORDER BY id").query(Long.class).list()
            .stream().map(this::trip).toList();
    }

    private TripRow trip(long tripId) {
        List<TripStopRow> stops = jdbc.sql("""
                SELECT s.stop_type, s.request_id, s.latitude, s.longitude, s.arrive_at, s.depart_at, s.status,
                  p.name AS point_name
                FROM cart_trip_stops s LEFT JOIN service_points p ON p.id = s.service_point_id
                WHERE s.trip_id=:trip ORDER BY s.stop_order
                """).param("trip", tripId)
            .query((rs, n) -> new TripStopRow(rs.getString("stop_type"),
                (Long) rs.getObject("request_id", Long.class),
                rs.getString("point_name") != null ? rs.getString("point_name") : "申请 #" + rs.getLong("request_id"),
                rs.getDouble("latitude"), rs.getDouble("longitude"),
                rs.getTimestamp("arrive_at").toInstant(), rs.getTimestamp("depart_at").toInstant(), rs.getString("status")))
            .list();
        return jdbc.sql("""
                SELECT t.id, t.cart_id, c.name AS cart_name, t.start_latitude, t.start_longitude, t.started_at,
                  t.distance_meters
                FROM cart_trips t JOIN mobile_carts c ON c.id = t.cart_id WHERE t.id=:id
                """).param("id", tripId)
            .query((rs, n) -> new TripRow(rs.getLong("id"), rs.getLong("cart_id"), rs.getString("cart_name"),
                rs.getDouble("start_latitude"), rs.getDouble("start_longitude"),
                rs.getTimestamp("started_at").toInstant(), rs.getInt("distance_meters"), stops)).single();
    }

    /** Approved delivery requests whose stock is held and which no trip has picked up yet. */
    private List<DeliveryDispatchAlgorithm.Job> pendingJobs(boolean lock) {
        return jdbc.sql("""
                SELECT r.id, res.id AS reservation_id, r.quantity, r.urgency, r.latitude, r.longitude,
                  p.id AS point_id, p.latitude AS point_lat, p.longitude AS point_lon
                FROM supply_requests r
                JOIN reservations res ON res.request_id = r.id AND res.status = 'HELD' AND res.expires_at > :minExpiry
                JOIN inventory_items i ON i.id = res.inventory_item_id
                JOIN service_points p ON p.id = i.service_point_id
                WHERE r.status = 'APPROVED' AND r.fulfillment_method = 'DELIVERY'
                  AND NOT EXISTS (SELECT 1 FROM cart_trip_stops s WHERE s.request_id = r.id AND s.status = 'PENDING')
                ORDER BY r.created_at
                """ + (lock ? " FOR UPDATE" : ""))
            .param("minExpiry", Timestamp.from(Instant.now().plus(MIN_REMAINING_HOLD)))
            .query((rs, n) -> new DeliveryDispatchAlgorithm.Job(rs.getLong("id"), rs.getLong("reservation_id"),
                rs.getInt("quantity"), rs.getString("urgency"),
                new DeliveryDispatchAlgorithm.Point(rs.getLong("point_id"), rs.getDouble("point_lat"), rs.getDouble("point_lon")),
                rs.getDouble("latitude"), rs.getDouble("longitude"))).list();
    }

    private long insertTrip(DeliveryDispatchAlgorithm.Trip trip, DeliveryDispatchAlgorithm.Cart cart, Long routePlanId,
                            Instant start) {
        org.springframework.jdbc.support.GeneratedKeyHolder keys = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO cart_trips (cart_id, route_plan_id, start_latitude, start_longitude, started_at, distance_meters)
                VALUES (:cart, :plan, :lat, :lon, :start, :distance)
                """).param("cart", trip.cartId()).param("plan", routePlanId).param("lat", cart.latitude())
            .param("lon", cart.longitude()).param("start", Timestamp.from(start))
            .param("distance", trip.distanceMeters()).update(keys);
        return keys.getKey().longValue();
    }

    private void insertStop(long tripId, int order, DeliveryDispatchAlgorithm.Stop stop) {
        jdbc.sql("""
                INSERT INTO cart_trip_stops (trip_id, stop_order, stop_type, service_point_id, request_id, reservation_id,
                  latitude, longitude, arrive_at, depart_at)
                VALUES (:trip, :order, :type, :point, :request, :reservation, :lat, :lon, :arrive, :depart)
                """).param("trip", tripId).param("order", order).param("type", stop.type().name())
            .param("point", stop.servicePointId()).param("request", stop.requestId())
            .param("reservation", stop.reservationId()).param("lat", stop.latitude()).param("lon", stop.longitude())
            .param("arrive", Timestamp.from(stop.arriveAt())).param("depart", Timestamp.from(stop.departAt())).update();
    }

    private void insertRouteStop(long routePlanId, long cartId, int order, DeliveryDispatchAlgorithm.Stop stop) {
        int demand = jdbc.sql("SELECT quantity FROM supply_requests WHERE id=:id").param("id", stop.requestId())
            .query(Integer.class).single();
        jdbc.sql("""
                INSERT INTO route_stops (route_plan_id, cart_id, request_id, stop_order, latitude, longitude,
                  demand_units, estimated_arrival)
                VALUES (:plan, :cart, :request, :order, :lat, :lon, :demand, :eta)
                """).param("plan", routePlanId).param("cart", cartId).param("request", stop.requestId())
            .param("order", order).param("lat", stop.latitude()).param("lon", stop.longitude())
            .param("demand", demand).param("eta", Timestamp.from(stop.arriveAt())).update();
    }

    public record PlanOutcome(List<Long> assignedRequestIds, long distanceMeters, long baselineMeters,
                              List<Long> unassignedRequestIds) {
    }
    public record CartMotion(double latitude, double longitude, String statusText, List<double[]> path) {
    }
    public record Tracking(long cartId, String cartName, double latitude, double longitude, String statusText,
                           Instant estimatedArrival, boolean delivered, double destinationLatitude,
                           double destinationLongitude, List<double[]> path) {
    }
    public record AdminStop(String type, String label, Long requestId, double latitude, double longitude,
                            Instant arriveAt, String status) {
    }
    public record AdminTrip(long tripId, long cartId, String cartName, double latitude, double longitude,
                            String statusText, int distanceMeters, List<AdminStop> stops) {
    }
    record DueStop(long id, String type, Long requestId, Long reservationId, java.math.BigDecimal latitude,
                   java.math.BigDecimal longitude) {
    }
    record TripStopRow(String type, Long requestId, String label, double latitude, double longitude,
                       Instant arriveAt, Instant departAt, String status) {
    }
    record TripRow(long id, long cartId, String cartName, double startLatitude, double startLongitude,
                   Instant startedAt, int distanceMeters, List<TripStopRow> stops) {
    }
}
