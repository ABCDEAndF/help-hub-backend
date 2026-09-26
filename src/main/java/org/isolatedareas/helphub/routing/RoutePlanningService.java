package org.isolatedareas.helphub.routing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.isolatedareas.helphub.audit.AuditService;
import org.isolatedareas.helphub.automation.CartTripService;
import org.isolatedareas.helphub.events.OutboxService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RoutePlanningService {
    private static final Logger log = LoggerFactory.getLogger(RoutePlanningService.class);
    private final JdbcClient jdbc;
    private final OutboxService outbox;
    private final AuditService audit;
    private final ObjectMapper json;
    private final CartTripService trips;

    public RoutePlanningService(JdbcClient jdbc, OutboxService outbox, AuditService audit, ObjectMapper json,
                                CartTripService trips) {
        this.trips = trips;
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.audit = audit;
        this.json = json;
    }

    @Transactional
    public RoutePlanView requestPlan(long operatorId, LocalDate serviceDate) {
        org.springframework.jdbc.support.GeneratedKeyHolder keys = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.sql("INSERT INTO route_plans (service_date, requested_by) VALUES (:date, :operatorId)")
            .param("date", Date.valueOf(serviceDate)).param("operatorId", operatorId).update(keys);
        long routePlanId = keys.getKey().longValue();
        outbox.append("ROUTE_PLAN", routePlanId, "RoutePlanRequested", "route.compute",
            Map.of("eventId", "route-plan-" + routePlanId, "routePlanId", routePlanId));
        audit.record(operatorId, "ROUTE_PLAN_REQUESTED", "ROUTE_PLAN", routePlanId, null,
            Map.of("serviceDate", serviceDate));
        return get(routePlanId);
    }

    @Transactional
    public void compute(long routePlanId) {
        int claimed = jdbc.sql("""
                UPDATE route_plans SET status='COMPUTING'
                WHERE id=:id AND status='PENDING'
                """).param("id", routePlanId).update();
        if (claimed == 0) return;
        try {
            // Every dispatch — the operator's "生成今日路线" or the automatic dispatcher — is computed here,
            // asynchronously from RabbitMQ, and immediately starts simulated cart trips.
            CartTripService.PlanOutcome result = trips.planTrips(routePlanId);
            jdbc.sql("""
                    UPDATE route_plans SET status='READY', objective_distance_meters=:distance,
                      baseline_distance_meters=:baseline, completed_at=CURRENT_TIMESTAMP(3) WHERE id=:id
                    """)
                .param("distance", result.distanceMeters())
                .param("baseline", result.baselineMeters())
                .param("id", routePlanId).update();
            outbox.append("ROUTE_PLAN", routePlanId, "RoutePlanReady", "notification.send",
                Map.of("eventId", "route-ready-" + routePlanId, "recipientRole", "OPERATOR",
                    "template", "ROUTE_READY", "routePlanId", routePlanId,
                    "unassignedRequests", result.unassignedRequestIds()));
        } catch (RuntimeException error) {
            jdbc.sql("UPDATE route_plans SET status='FAILED', error_message=:error WHERE id=:id")
                .param("error", truncate(error.getMessage())).param("id", routePlanId).update();
            log.error("Route plan {} failed", routePlanId, error);
        }
    }

    public RoutePlanView get(long id) {
        RoutePlanSummary summary = jdbc.sql("""
                SELECT id, service_date, status, objective_distance_meters, baseline_distance_meters,
                  requested_by, created_at, completed_at, error_message
                FROM route_plans WHERE id=:id
                """).param("id", id)
            .query((rs, n) -> new RoutePlanSummary(rs.getLong("id"), rs.getDate("service_date").toLocalDate(),
                rs.getString("status"), nullableInt(rs, "objective_distance_meters"),
                nullableInt(rs, "baseline_distance_meters"), rs.getLong("requested_by"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant(),
                rs.getString("error_message"))).optional()
            .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "Route plan not found"));
        List<RouteStopView> stops = jdbc.sql("""
                SELECT id, cart_id, request_id, stop_order, latitude, longitude, demand_units, estimated_arrival
                FROM route_stops WHERE route_plan_id=:id ORDER BY cart_id, stop_order
                """).param("id", id)
            .query((rs, n) -> new RouteStopView(rs.getLong("id"), rs.getLong("cart_id"),
                rs.getLong("request_id"), rs.getInt("stop_order"), rs.getDouble("latitude"),
                rs.getDouble("longitude"), rs.getInt("demand_units"),
                rs.getTimestamp("estimated_arrival") == null ? null : rs.getTimestamp("estimated_arrival").toInstant()))
            .list();
        return new RoutePlanView(summary, stops);
    }

    public List<RoutePlanSummary> list() {
        return jdbc.sql("""
                SELECT id, service_date, status, objective_distance_meters, baseline_distance_meters,
                  requested_by, created_at, completed_at, error_message
                FROM route_plans ORDER BY created_at DESC LIMIT 100
                """)
            .query((rs, n) -> new RoutePlanSummary(rs.getLong("id"), rs.getDate("service_date").toLocalDate(),
                rs.getString("status"), nullableInt(rs, "objective_distance_meters"),
                nullableInt(rs, "baseline_distance_meters"), rs.getLong("requested_by"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant(),
                rs.getString("error_message"))).list();
    }

    private static Integer nullableInt(java.sql.ResultSet rs, String name) throws java.sql.SQLException {
        int value = rs.getInt(name);
        return rs.wasNull() ? null : value;
    }

    private String truncate(String value) {
        if (value == null) return "Unknown route planning failure";
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }

    public record RoutePlanSummary(long id, LocalDate serviceDate, String status, Integer distanceMeters,
                                   Integer baselineDistanceMeters, long requestedBy, java.time.Instant createdAt,
                                   java.time.Instant completedAt, String errorMessage) {
    }
    public record RouteStopView(long id, long cartId, long requestId, int stopOrder, double latitude,
                                double longitude, int demandUnits, java.time.Instant estimatedArrival) {
    }
    public record RoutePlanView(RoutePlanSummary summary, List<RouteStopView> stops) {
    }
}
