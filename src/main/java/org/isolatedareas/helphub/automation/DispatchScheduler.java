package org.isolatedareas.helphub.automation;

import java.time.LocalDate;
import java.time.ZoneId;
import org.isolatedareas.helphub.routing.RoutePlanningService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Requests a route plan whenever a reserved delivery is waiting and a cart is idle. The plan
 * itself is computed asynchronously by the RabbitMQ route consumer, exactly like an
 * operator-requested plan, so a single delivery makes the nearest cart set off within a minute.
 */
@Component
public class DispatchScheduler {
    private static final Logger log = LoggerFactory.getLogger(DispatchScheduler.class);
    private final CartTripService trips;
    private final RoutePlanningService routes;
    private final SystemActor system;
    private final JdbcClient jdbc;

    public DispatchScheduler(CartTripService trips, RoutePlanningService routes, SystemActor system, JdbcClient jdbc) {
        this.trips = trips;
        this.routes = routes;
        this.system = system;
        this.jdbc = jdbc;
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void dispatchWaitingDeliveries() {
        try {
            // Only recent plans count, so a plan stuck by a broker outage cannot block dispatch forever.
            int inFlight = jdbc.sql("""
                    SELECT COUNT(*) FROM route_plans
                    WHERE status IN ('PENDING','COMPUTING') AND created_at > CURRENT_TIMESTAMP(3) - INTERVAL 10 MINUTE
                    """).query(Integer.class).single();
            if (inFlight > 0 || !trips.dispatchNeeded()) return;
            routes.requestPlan(system.id(), LocalDate.now(ZoneId.of("Asia/Shanghai")));
        } catch (RuntimeException error) {
            log.warn("Automatic dispatch check failed; retrying on the next cycle", error);
        }
    }
}
