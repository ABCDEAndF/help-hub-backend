package org.isolatedareas.helphub.automation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.isolatedareas.helphub.automation.DeliveryDispatchAlgorithm.Cart;
import org.isolatedareas.helphub.automation.DeliveryDispatchAlgorithm.Job;
import org.isolatedareas.helphub.automation.DeliveryDispatchAlgorithm.Point;
import org.isolatedareas.helphub.automation.DeliveryDispatchAlgorithm.StopType;
import org.junit.jupiter.api.Test;

class DeliveryDispatchAlgorithmTest {
    private static final Point XIAYANG = new Point(1, 31.1518500, 121.1294200);
    private static final Point INDUSTRIAL = new Point(2, 31.1811800, 121.0926800);
    private static final Instant START = Instant.parse("2026-09-26T02:00:00Z");
    private final DeliveryDispatchAlgorithm algorithm = new DeliveryDispatchAlgorithm();

    @Test
    void aSingleDeliveryUsesTheCartNearestToTheStockThenDeliversAndReturns() {
        var nearXiayang = new Cart(1, 120, 31.1518500, 121.1294200);
        var nearIndustrial = new Cart(2, 100, 31.1811800, 121.0926800);
        var job = new Job(7, 70, 1, "NORMAL", XIAYANG, 31.1600, 121.1400);

        var trips = algorithm.plan(List.of(nearIndustrial, nearXiayang), List.of(job), List.of(XIAYANG, INDUSTRIAL), START);

        assertThat(trips).hasSize(1);
        var trip = trips.getFirst();
        assertThat(trip.cartId()).isEqualTo(1);
        assertThat(trip.stops()).extracting(DeliveryDispatchAlgorithm.Stop::type)
            .containsExactly(StopType.PICKUP, StopType.DROPOFF, StopType.RETURN);
        assertThat(trip.stops().get(0).servicePointId()).isEqualTo(1);
        assertThat(trip.stops().get(1).requestId()).isEqualTo(7);
        assertThat(trip.stops().get(1).reservationId()).isEqualTo(70);
        // Already parked at the pickup point: arrives at once, loads for five minutes.
        assertThat(trip.stops().get(0).arriveAt()).isEqualTo(START);
        assertThat(trip.stops().get(0).departAt()).isEqualTo(START.plus(Duration.ofMinutes(5)));
        assertThat(trip.stops().get(1).arriveAt()).isAfter(trip.stops().get(0).departAt());
        assertThat(trip.distanceMeters()).isPositive();
    }

    @Test
    void travelTimeFollowsTheEstimatedUrbanSpeed() {
        // 25 km/h: 1 km of road takes 144 seconds.
        assertThat(DeliveryDispatchAlgorithm.travel(1000)).isEqualTo(Duration.ofSeconds(144));
    }

    @Test
    void urgentJobsAreServedFirstAndCapacityIsRespected() {
        var small = new Cart(1, 3, 31.1518500, 121.1294200);
        var urgent = new Job(1, 10, 3, "CRITICAL", XIAYANG, 31.16, 121.14);
        var normal = new Job(2, 20, 2, "NORMAL", XIAYANG, 31.15, 121.13);

        var trips = algorithm.plan(List.of(small), List.of(normal, urgent), List.of(XIAYANG), START);

        assertThat(trips).hasSize(1);
        assertThat(trips.getFirst().stops()).filteredOn(stop -> stop.type() == StopType.DROPOFF)
            .extracting(DeliveryDispatchAlgorithm.Stop::requestId).containsExactly(1L);
    }

    @Test
    void aCartCollectsFromEveryPointItNeedsBeforeDelivering() {
        var cart = new Cart(1, 120, 31.1518500, 121.1294200);
        var fromXiayang = new Job(1, 10, 1, "NORMAL", XIAYANG, 31.16, 121.12);
        var fromIndustrial = new Job(2, 20, 1, "NORMAL", INDUSTRIAL, 31.17, 121.10);

        var stops = algorithm.plan(List.of(cart), List.of(fromXiayang, fromIndustrial), List.of(XIAYANG, INDUSTRIAL), START)
            .getFirst().stops();

        assertThat(stops).extracting(DeliveryDispatchAlgorithm.Stop::type)
            .containsExactly(StopType.PICKUP, StopType.PICKUP, StopType.DROPOFF, StopType.DROPOFF, StopType.RETURN);
        for (int i = 1; i < stops.size(); i++) {
            assertThat(stops.get(i).arriveAt()).isAfterOrEqualTo(stops.get(i - 1).departAt());
        }
    }

    @Test
    void noCartMeansNoTrip() {
        var job = new Job(1, 10, 1, "NORMAL", XIAYANG, 31.16, 121.12);
        assertThat(algorithm.plan(List.of(), List.of(job), List.of(XIAYANG), START)).isEmpty();
    }
}
