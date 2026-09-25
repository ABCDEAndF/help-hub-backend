package org.isolatedareas.helphub.impact;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.isolatedareas.helphub.geo.DemandClusterService;

@Service
public class ImpactService {
    private final JdbcClient jdbc;
    private final DemandClusterService clusters;

    public ImpactService(JdbcClient jdbc, DemandClusterService clusters) {
        this.jdbc = jdbc;
        this.clusters = clusters;
    }

    public ImpactSummary summary() {
        Counts counts = jdbc.sql("""
                SELECT COUNT(*) AS total,
                  SUM(status='FULFILLED') AS fulfilled,
                  COUNT(DISTINCT CASE WHEN status='FULFILLED' THEN resident_id END) AS residents,
                  SUM(status IN ('REJECTED','CANCELLED')) AS closed_without_service
                FROM supply_requests
                """).query((rs, n) -> new Counts(rs.getLong("total"), rs.getLong("fulfilled"),
                rs.getLong("residents"), rs.getLong("closed_without_service"))).single();
        List<Long> fulfillmentMinutes = jdbc.sql("""
                SELECT TIMESTAMPDIFF(MINUTE, created_at, fulfilled_at) AS minutes
                FROM supply_requests WHERE status='FULFILLED' AND fulfilled_at IS NOT NULL
                """).query(Long.class).list();
        long eligible = Math.max(0, counts.total() - counts.closedWithoutService());
        double fulfillmentRate = eligible == 0 ? 0 : (double) counts.fulfilled() / eligible;
        RouteImpact route = jdbc.sql("""
                SELECT COALESCE(SUM(objective_distance_meters),0) AS optimized,
                  COALESCE(SUM(baseline_distance_meters),0) AS baseline,
                  COUNT(*) AS routes
                FROM route_plans WHERE status IN ('READY','DISPATCHED','COMPLETED')
                """).query((rs, n) -> new RouteImpact(rs.getLong("routes"), rs.getLong("optimized"),
                rs.getLong("baseline"))).single();
        FeedbackImpact feedback = jdbc.sql("""
                SELECT COUNT(*) AS responses, COALESCE(AVG(rating),0) AS rating,
                  COALESCE(AVG(GREATEST(previous_travel_minutes-current_travel_minutes,0)),0) AS saved_minutes
                FROM service_feedback
                """).query((rs, n) -> new FeedbackImpact(rs.getLong("responses"), rs.getDouble("rating"),
                rs.getDouble("saved_minutes"))).single();
        HouseholdImpact household = jdbc.sql("""
                SELECT COUNT(*) AS households, COALESCE(SUM(u.household_size),0) AS people
                FROM users u JOIN (
                  SELECT DISTINCT resident_id FROM supply_requests WHERE status='FULFILLED'
                ) served ON served.resident_id=u.id
                """).query((rs, n) -> new HouseholdImpact(rs.getLong("households"),
                rs.getLong("people"))).single();
        ReservationImpact reservations = jdbc.sql("""
                SELECT COUNT(*) AS total, SUM(status='EXPIRED') AS expired FROM reservations
                """).query((rs, n) -> new ReservationImpact(rs.getLong("total"), rs.getLong("expired"))).single();
        long stockouts = jdbc.sql("SELECT COUNT(*) FROM impact_events WHERE event_type='STOCKOUT_RECORDED' AND is_test=FALSE")
            .query(Long.class).single();
        double expirationRate = reservations.total() == 0 ? 0 : (double) reservations.expired() / reservations.total();
        double stockoutRate = reservations.total() + stockouts == 0 ? 0
            : (double) stockouts / (reservations.total() + stockouts);
        return new ImpactSummary(counts.total(), counts.fulfilled(), counts.residents(), fulfillmentRate,
            median(fulfillmentMinutes), route.routes(), Math.max(0, route.baselineMeters() - route.optimizedMeters()),
            feedback.responses(), feedback.averageRating(), feedback.averageTravelMinutesSaved(),
            household.households(), household.people(), clusters.fulfilledClusterCount(1500, 2),
            stockouts, stockoutRate, expirationRate);
    }

    private long median(List<Long> values) {
        if (values.isEmpty()) return 0;
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int middle = sorted.size() / 2;
        return sorted.size() % 2 == 1 ? sorted.get(middle) : (sorted.get(middle - 1) + sorted.get(middle)) / 2;
    }

    record Counts(long total, long fulfilled, long residents, long closedWithoutService) {
    }
    record RouteImpact(long routes, long optimizedMeters, long baselineMeters) {
    }
    record FeedbackImpact(long responses, double averageRating, double averageTravelMinutesSaved) {
    }
    record HouseholdImpact(long households, long people) {
    }
    record ReservationImpact(long total, long expired) {
    }
    public record ImpactSummary(long totalRequests, long fulfilledRequests, long uniqueResidentsServed,
                                double fulfillmentRate, long medianFulfillmentMinutes, long completedRoutePlans,
                                long routeMetersSaved, long feedbackResponses, double averageSatisfaction,
                                double averageTravelMinutesSaved, long householdsServed, long peopleServed,
                                long demandClustersReached, long stockoutIncidents, double stockoutRate,
                                double reservationExpirationRate) {
    }
}
