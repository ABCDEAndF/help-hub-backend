package org.isolatedareas.helphub.routing;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import org.junit.jupiter.api.Test;

class RoutePlanningAlgorithmTest {
    private final RoutePlanningAlgorithm algorithm = new RoutePlanningAlgorithm();

    @Test
    void neverExceedsCapacityAndReportsUnassignedDemand() {
        var result = algorithm.plan(
            List.of(new RoutePlanningAlgorithm.CartInput(1, 5, 31.23, 121.47)),
            List.of(
                new RoutePlanningAlgorithm.StopInput(1, 3, "NORMAL", 31.231, 121.471),
                new RoutePlanningAlgorithm.StopInput(2, 3, "NORMAL", 31.232, 121.472)));
        assertThat(result.routes()).hasSize(1);
        assertThat(result.routes().getFirst().assignedUnits()).isLessThanOrEqualTo(5);
        assertThat(result.unassignedRequestIds()).hasSize(1);
    }

    @Test
    void servesCriticalRequestBeforeCloserNormalRequest() {
        var result = algorithm.plan(
            List.of(new RoutePlanningAlgorithm.CartInput(1, 1, 31.23, 121.47)),
            List.of(
                new RoutePlanningAlgorithm.StopInput(10, 1, "NORMAL", 31.2301, 121.4701),
                new RoutePlanningAlgorithm.StopInput(20, 1, "CRITICAL", 31.25, 121.49)));
        assertThat(result.routes().getFirst().stops().getFirst().requestId()).isEqualTo(20);
        assertThat(result.unassignedRequestIds()).containsExactly(10L);
    }

    @Test
    void optimizedSharedRouteDoesNotExceedIndependentTrips() {
        var result = algorithm.plan(
            List.of(new RoutePlanningAlgorithm.CartInput(1, 10, 31.23, 121.47)),
            List.of(
                new RoutePlanningAlgorithm.StopInput(1, 1, "NORMAL", 31.231, 121.471),
                new RoutePlanningAlgorithm.StopInput(2, 1, "NORMAL", 31.232, 121.472),
                new RoutePlanningAlgorithm.StopInput(3, 1, "NORMAL", 31.233, 121.473)));
        assertThat(result.totalDistanceMeters()).isLessThanOrEqualTo(result.baselineDistanceMeters());
    }
}
