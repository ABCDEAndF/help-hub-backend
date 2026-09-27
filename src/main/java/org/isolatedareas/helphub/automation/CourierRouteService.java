package org.isolatedareas.helphub.automation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.isolatedareas.helphub.audit.AuditService;
import org.isolatedareas.helphub.auth.UserAccount;
import org.isolatedareas.helphub.auth.UserRepository;
import org.isolatedareas.helphub.domain.Role;
import org.isolatedareas.helphub.geo.GeoMath;
import org.isolatedareas.helphub.requests.ServiceWindow;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * One courier per cart. A courier sees their cart's current trip as legs — from wherever the
 * cart is to each pickup point, delivery address and the return point — each with the
 * destination to navigate to and what to load or hand over there.
 */
@Service
public class CourierRouteService {
    /** A trip that starts this close to a service point is described as starting there. */
    private static final double SAME_PLACE_METERS = 200;

    private final JdbcClient jdbc;
    private final CartTripService trips;
    private final UserRepository users;
    private final AuditService audit;

    public CourierRouteService(JdbcClient jdbc, CartTripService trips, UserRepository users, AuditService audit) {
        this.jdbc = jdbc;
        this.trips = trips;
        this.users = users;
        this.audit = audit;
    }

    public List<CartCourier> carts() {
        return jdbc.sql("""
                SELECT c.id, c.code, c.name, c.status, u.phone AS account, u.display_name AS courier_name
                FROM mobile_carts c LEFT JOIN users u ON u.id = c.courier_id ORDER BY c.id
                """)
            .query((rs, n) -> new CartCourier(rs.getLong("id"), rs.getString("code"), rs.getString("name"),
                rs.getString("status"), rs.getString("account"), rs.getString("courier_name"))).list();
    }

    /** Binds a staff account to a cart (moving it off any other cart), or unbinds with a blank account. */
    @Transactional
    public CartCourier assign(long actorId, long cartId, String account) {
        CartCourier before = carts().stream().filter(cart -> cart.id() == cartId).findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Cart not found"));
        Long courierId = null;
        if (account != null && !account.isBlank()) {
            UserAccount staff = users.findByPhone(account.trim())
                .filter(user -> user.role() == Role.OPERATOR || user.role() == Role.ADMIN)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Staff account not found"));
            courierId = staff.id();
            jdbc.sql("UPDATE mobile_carts SET courier_id=NULL WHERE courier_id=:courier AND id<>:cart")
                .param("courier", courierId).param("cart", cartId).update();
        }
        jdbc.sql("UPDATE mobile_carts SET courier_id=:courier, version=version+1 WHERE id=:id")
            .param("courier", courierId).param("id", cartId).update();
        CartCourier after = carts().stream().filter(cart -> cart.id() == cartId).findFirst().orElseThrow();
        audit.record(actorId, "CART_COURIER_ASSIGNED", "MOBILE_CART", cartId, before, after);
        return after;
    }

    /** The route of the cart this staff member drives; {@code assigned=false} when they drive none. */
    public CourierRoute routeFor(long courierId, Instant now) {
        Optional<CartRow> found = jdbc.sql("""
                SELECT id, name, latitude, longitude FROM mobile_carts WHERE courier_id=:courier
                """).param("courier", courierId)
            .query((rs, n) -> new CartRow(rs.getLong("id"), rs.getString("name"), rs.getDouble("latitude"),
                rs.getDouble("longitude"))).optional();
        if (found.isEmpty()) return new CourierRoute(false, null, null, null, null, null, null, List.of());
        CartRow cart = found.get();
        List<PointRow> points = jdbc.sql("SELECT id, name, address, latitude, longitude FROM service_points ORDER BY id")
            .query((rs, n) -> new PointRow(rs.getLong("id"), rs.getString("name"), rs.getString("address"),
                rs.getDouble("latitude"), rs.getDouble("longitude"))).list();

        Optional<TripStart> trip = jdbc.sql("""
                SELECT id, start_latitude, start_longitude FROM cart_trips
                WHERE cart_id=:cart AND status='ACTIVE' ORDER BY id DESC LIMIT 1
                """).param("cart", cart.id())
            .query((rs, n) -> new TripStart(rs.getLong("id"), rs.getDouble("start_latitude"),
                rs.getDouble("start_longitude"))).optional();
        if (trip.isEmpty()) {
            return new CourierRoute(true, cart.id(), cart.name(), "暂无配送任务，车辆停在" + placeName(points,
                cart.latitude(), cart.longitude(), "当前位置"), cart.latitude(), cart.longitude(), null, List.of());
        }

        List<StopRow> stops = stops(trip.get().id());
        List<Leg> legs = new ArrayList<>();
        String fromName = placeName(points, trip.get().latitude(), trip.get().longitude(), "出发位置");
        double fromLat = trip.get().latitude();
        double fromLon = trip.get().longitude();
        boolean currentFound = false;
        for (StopRow stop : stops) {
            String state;
            if ("DONE".equals(stop.status())) {
                state = "DONE";
            } else if (!currentFound) {
                state = "CURRENT";
                currentFound = true;
            } else {
                state = "UPCOMING";
            }
            String toName;
            String toAddress;
            List<String> details;
            switch (stop.type()) {
                case "PICKUP" -> {
                    toName = "取货：" + stop.pointName();
                    toAddress = stop.pointAddress();
                    details = loadList(trip.get().id(), stop.servicePointId());
                }
                case "DROPOFF" -> {
                    Dropoff dropoff = dropoff(stop.requestId(), stop.reservationId());
                    toName = "送达：" + dropoff.residentName() + "（第 " + dropoff.residentNumber() + " 个申请）";
                    toAddress = dropoff.address() == null || dropoff.address().isBlank() ? "按地图标记位置" : dropoff.address();
                    details = new ArrayList<>();
                    details.add(dropoff.itemName() + " × " + dropoff.quantity() + dropoff.unit());
                    if (dropoff.windowStart() != null && dropoff.windowEnd() != null) {
                        details.add("居民预约：" + ServiceWindow.describe(dropoff.windowStart(), dropoff.windowEnd()));
                    }
                    if (dropoff.notes() != null && !dropoff.notes().isBlank()) details.add("备注：" + dropoff.notes());
                    details.add("交付时请居民出示 6 位领取码并在下方核销");
                }
                default -> {
                    toName = "返回：" + stop.pointName();
                    toAddress = stop.pointAddress();
                    details = List.of("本趟配送结束后回到服务点待命");
                }
            }
            legs.add(new Leg(stop.order(), stop.type(), fromName, fromLat, fromLon, toName, toAddress,
                stop.latitude(), stop.longitude(), stop.arriveAt(), state, details));
            fromName = "PICKUP".equals(stop.type()) || "RETURN".equals(stop.type()) ? stop.pointName()
                : toName.substring("送达：".length());
            fromLat = stop.latitude();
            fromLon = stop.longitude();
        }
        CartTripService.CartMotion motion = trips.motions(now).get(cart.id());
        double lat = motion == null ? cart.latitude() : motion.latitude();
        double lon = motion == null ? cart.longitude() : motion.longitude();
        String status = motion == null ? "配送中" : motion.statusText();
        return new CourierRoute(true, cart.id(), cart.name(), status, lat, lon, trip.get().id(), legs);
    }

    private List<StopRow> stops(long tripId) {
        return jdbc.sql("""
                SELECT s.stop_order, s.stop_type, s.service_point_id, s.request_id, s.reservation_id,
                  s.latitude, s.longitude, s.arrive_at, s.status, p.name AS point_name, p.address AS point_address
                FROM cart_trip_stops s LEFT JOIN service_points p ON p.id = s.service_point_id
                WHERE s.trip_id=:trip ORDER BY s.stop_order
                """).param("trip", tripId)
            .query((rs, n) -> new StopRow(rs.getInt("stop_order"), rs.getString("stop_type"),
                (Long) rs.getObject("service_point_id", Long.class), (Long) rs.getObject("request_id", Long.class),
                (Long) rs.getObject("reservation_id", Long.class), rs.getDouble("latitude"), rs.getDouble("longitude"),
                rs.getTimestamp("arrive_at").toInstant(), rs.getString("status"), rs.getString("point_name"),
                rs.getString("point_address"))).list();
    }

    /** What to load at a pickup point: the reserved stock of this trip's deliveries kept there. */
    private List<String> loadList(long tripId, Long servicePointId) {
        List<String> lines = jdbc.sql("""
                SELECT i.name, res.quantity, i.unit, u.display_name
                FROM cart_trip_stops d
                JOIN reservations res ON res.id = d.reservation_id
                JOIN inventory_items i ON i.id = res.inventory_item_id
                JOIN supply_requests r ON r.id = d.request_id
                JOIN users u ON u.id = r.resident_id
                WHERE d.trip_id=:trip AND d.stop_type='DROPOFF' AND i.service_point_id=:point
                ORDER BY d.stop_order
                """).param("trip", tripId).param("point", servicePointId)
            .query((rs, n) -> "装车：" + rs.getString("name") + " × " + rs.getInt("quantity") + rs.getString("unit")
                + "（送给 " + rs.getString("display_name") + "）").list();
        return lines.isEmpty() ? List.of("本站无需装货") : lines;
    }

    private Dropoff dropoff(Long requestId, Long reservationId) {
        return jdbc.sql("""
                SELECT u.display_name, r.resident_seq, r.approximate_address, r.accessibility_notes,
                  r.preferred_start, r.preferred_end, COALESCE(i.name, r.item_description) AS item_name,
                  COALESCE(res.quantity, r.quantity) AS quantity, COALESCE(i.unit, '') AS unit
                FROM supply_requests r
                JOIN users u ON u.id = r.resident_id
                LEFT JOIN reservations res ON res.id = :reservation
                LEFT JOIN inventory_items i ON i.id = res.inventory_item_id
                WHERE r.id = :request
                """).param("request", requestId).param("reservation", reservationId)
            .query((rs, n) -> new Dropoff(rs.getString("display_name"), rs.getInt("resident_seq"),
                rs.getString("approximate_address"), rs.getString("accessibility_notes"),
                rs.getTimestamp("preferred_start") == null ? null : rs.getTimestamp("preferred_start").toInstant(),
                rs.getTimestamp("preferred_end") == null ? null : rs.getTimestamp("preferred_end").toInstant(),
                rs.getString("item_name"), rs.getInt("quantity"), rs.getString("unit"))).single();
    }

    private static String placeName(List<PointRow> points, double lat, double lon, String fallback) {
        return points.stream()
            .filter(point -> GeoMath.distanceMeters(lat, lon, point.latitude(), point.longitude()) <= SAME_PLACE_METERS)
            .map(PointRow::name).findFirst().orElse(fallback);
    }

    public record CartCourier(long id, String code, String name, String status, String courierAccount,
                              String courierName) {
    }
    /** state: DONE, CURRENT (the leg being driven now) or UPCOMING. */
    public record Leg(int order, String type, String fromName, double fromLatitude, double fromLongitude,
                      String toName, String toAddress, double toLatitude, double toLongitude, Instant arriveAt,
                      String state, List<String> details) {
    }
    public record CourierRoute(boolean assigned, Long cartId, String cartName, String statusText, Double latitude,
                               Double longitude, Long tripId, List<Leg> legs) {
    }
    record CartRow(long id, String name, double latitude, double longitude) {
    }
    record PointRow(long id, String name, String address, double latitude, double longitude) {
    }
    record TripStart(long id, double latitude, double longitude) {
    }
    record StopRow(int order, String type, Long servicePointId, Long requestId, Long reservationId, double latitude,
                   double longitude, Instant arriveAt, String status, String pointName, String pointAddress) {
    }
    record Dropoff(String residentName, int residentNumber, String address, String notes, Instant windowStart,
                   Instant windowEnd, String itemName, int quantity, String unit) {
    }
}
