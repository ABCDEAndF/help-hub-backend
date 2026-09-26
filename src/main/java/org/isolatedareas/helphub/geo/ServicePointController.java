package org.isolatedareas.helphub.geo;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import org.isolatedareas.helphub.automation.CartTripService;
import org.isolatedareas.helphub.auth.CurrentUser;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ServicePointController {
    private final JdbcClient jdbc;
    private final StringRedisTemplate redis;

    private final CartTripService trips;

    public ServicePointController(JdbcClient jdbc, StringRedisTemplate redis, CartTripService trips) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.trips = trips;
    }

    @GetMapping("/resident/service-points")
    List<ServicePointView> nearby(
        @RequestParam @DecimalMin("30.0") @DecimalMax("32.0") BigDecimal latitude,
        @RequestParam @DecimalMin("120.0") @DecimalMax("123.0") BigDecimal longitude,
        @RequestParam(defaultValue = "10000") int radiusMeters
    ) {
        int radius = Math.min(Math.max(radiusMeters, 100), 50_000);
        return jdbc.sql("""
                SELECT id, name, address, latitude, longitude, status, opens_at, closes_at,
                  ST_Distance_Sphere(POINT(longitude, latitude), POINT(:longitude, :latitude)) AS distance_meters
                FROM service_points
                WHERE status='ACTIVE'
                HAVING distance_meters <= :radius
                ORDER BY distance_meters LIMIT 50
                """)
            .param("latitude", latitude).param("longitude", longitude).param("radius", radius)
            .query((rs, n) -> new ServicePointView(rs.getLong("id"), rs.getString("name"),
                rs.getString("address"), rs.getBigDecimal("latitude"), rs.getBigDecimal("longitude"),
                rs.getString("status"), rs.getObject("opens_at", LocalTime.class),
                rs.getObject("closes_at", LocalTime.class), rs.getDouble("distance_meters")))
            .list();
    }

    @GetMapping("/resident/carts")
    List<CartView> carts() {
        Instant now = Instant.now();
        Map<Long, CartTripService.CartMotion> motions = trips.motions(now);
        return jdbc.sql("""
                SELECT id, code, name, capacity_units, status, latitude, longitude, last_location_at
                FROM mobile_carts WHERE status IN ('AVAILABLE','LOADING','IN_SERVICE') ORDER BY name
                """)
            .query((rs, n) -> {
                long id = rs.getLong("id");
                String cached = redis.opsForValue().get("cart-location:" + id);
                BigDecimal latitude = rs.getBigDecimal("latitude");
                BigDecimal longitude = rs.getBigDecimal("longitude");
                Instant observedAt = rs.getTimestamp("last_location_at") == null ? null
                    : rs.getTimestamp("last_location_at").toInstant();
                if (cached != null) {
                    String[] parts = cached.split(",");
                    if (parts.length == 3) {
                        latitude = new BigDecimal(parts[0]);
                        longitude = new BigDecimal(parts[1]);
                        observedAt = Instant.ofEpochMilli(Long.parseLong(parts[2]));
                    }
                }
                CartTripService.CartMotion motion = motions.get(id);
                if (motion != null) {
                    // A cart on a simulated trip is wherever its plan puts it right now.
                    return new CartView(id, rs.getString("code"), rs.getString("name"), rs.getInt("capacity_units"),
                        rs.getString("status"), BigDecimal.valueOf(motion.latitude()), BigDecimal.valueOf(motion.longitude()),
                        now, true, motion.statusText(), motion.path());
                }
                return new CartView(id, rs.getString("code"), rs.getString("name"),
                    rs.getInt("capacity_units"), rs.getString("status"), latitude, longitude, observedAt,
                    false, "待命", List.of());
            }).list();
    }

    @PutMapping("/admin/carts/{id}/location")
    @Transactional
    CartView updateCartLocation(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable long id,
        @Valid @RequestBody LocationUpdate input
    ) {
        int changed = jdbc.sql("""
                UPDATE mobile_carts SET latitude=:latitude, longitude=:longitude,
                  last_location_at=CURRENT_TIMESTAMP(3), version=version+1 WHERE id=:id
                """)
            .param("latitude", input.latitude()).param("longitude", input.longitude()).param("id", id).update();
        if (changed != 1) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "Cart not found");
        }
        String cacheValue = input.latitude() + "," + input.longitude() + "," + System.currentTimeMillis();
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
            new org.springframework.transaction.support.TransactionSynchronization() {
                @Override public void afterCommit() {
                    redis.opsForValue().set("cart-location:" + id, cacheValue, java.time.Duration.ofMinutes(5));
                }
            });
        jdbc.sql("""
                INSERT INTO audit_log (actor_user_id, action, entity_type, entity_id, after_data, correlation_id)
                VALUES (:actor, 'CART_LOCATION_UPDATED', 'MOBILE_CART', :id,
                  JSON_OBJECT('latitude', :latitude, 'longitude', :longitude), UUID())
                """)
            .param("actor", CurrentUser.id(jwt)).param("id", id)
            .param("latitude", input.latitude()).param("longitude", input.longitude()).update();
        return carts().stream().filter(cart -> cart.id() == id).findFirst().orElseThrow();
    }

    public record ServicePointView(long id, String name, String address, BigDecimal latitude,
                                   BigDecimal longitude, String status, LocalTime opensAt,
                                   LocalTime closesAt, double distanceMeters) {
    }

    public record CartView(long id, String code, String name, int capacityUnits, String status,
                           BigDecimal latitude, BigDecimal longitude, Instant observedAt,
                           boolean moving, String statusText, List<double[]> path) {
    }

    public record LocationUpdate(
        @NotNull @DecimalMin("30.0") @DecimalMax("32.0") BigDecimal latitude,
        @NotNull @DecimalMin("120.0") @DecimalMax("123.0") BigDecimal longitude
    ) {
    }
}

