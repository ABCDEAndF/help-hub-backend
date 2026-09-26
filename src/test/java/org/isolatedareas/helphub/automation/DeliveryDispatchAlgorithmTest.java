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
    void twoOptRemovesACrossingDeliveryOrder() {
        // Four homes on a square east of the start; visiting them corner-to-opposite-corner crosses itself.
        var a = new Job(1, 11, 1, "NORMAL", XIAYANG, 31.1600, 121.1400);
        var b = new Job(2, 12, 1, "NORMAL", XIAYANG, 31.1600, 121.1600);
        var c = new Job(3, 13, 1, "NORMAL", XIAYANG, 31.1400, 121.1600);
        var d = new Job(4, 14, 1, "NORMAL", XIAYANG, 31.1400, 121.1400);
        List<Job> crossing = List.of(a, c, b, d);
        double before = DeliveryDispatchAlgorithm.dropsLength(XIAYANG.latitude(), XIAYANG.longitude(), crossing, List.of(XIAYANG));

        List<Job> improved = DeliveryDispatchAlgorithm.twoOpt(XIAYANG.latitude(), XIAYANG.longitude(), crossing, List.of(XIAYANG));

        double after = DeliveryDispatchAlgorithm.dropsLength(XIAYANG.latitude(), XIAYANG.longitude(), improved, List.of(XIAYANG));
        assertThat(after).isLessThan(before * 0.9);
        assertThat(improved).containsExactlyInAnyOrder(a, b, c, d);
    }

    @Test
    void jobsInOppositeDirectionsAreSplitAcrossIdleCarts() {
        var one = new Cart(1, 120, XIAYANG.latitude(), XIAYANG.longitude());
        var two = new Cart(2, 120, XIAYANG.latitude(), XIAYANG.longitude());
        var eastA = new Job(1, 11, 1, "NORMAL", XIAYANG, 31.1520, 121.1800);
        var eastB = new Job(2, 12, 1, "NORMAL", XIAYANG, 31.1540, 121.1820);
        var westA = new Job(3, 13, 1, "NORMAL", XIAYANG, 31.1520, 121.0800);
        var westB = new Job(4, 14, 1, "NORMAL", XIAYANG, 31.1540, 121.0780);

        var trips = algorithm.plan(List.of(one, two), List.of(eastA, eastB, westA, westB), List.of(XIAYANG), START);

        assertThat(trips).hasSize(2);
        for (var trip : trips) {
            java.util.Set<Long> delivered = trip.stops().stream().filter(stop -> stop.type() == StopType.DROPOFF)
                .map(DeliveryDispatchAlgorithm.Stop::requestId).collect(java.util.stream.Collectors.toSet());
            assertThat(delivered).isIn(java.util.Set.of(1L, 2L), java.util.Set.of(3L, 4L));
        }
    }

    @Test
    void twelveRequestsAreAllPlannedWithAConsistentTimeline() {
        var one = new Cart(1, 120, XIAYANG.latitude(), XIAYANG.longitude());
        var two = new Cart(2, 100, INDUSTRIAL.latitude(), INDUSTRIAL.longitude());
        List<Job> jobs = new java.util.ArrayList<>();
        for (int i = 0; i < 12; i++) {
            Point pickup = i % 2 == 0 ? XIAYANG : INDUSTRIAL;
            jobs.add(new Job(i + 1, 100 + i, 2, i == 5 ? "CRITICAL" : "NORMAL", pickup,
                31.14 + (i % 4) * 0.012, 121.08 + (i / 4) * 0.025));
        }

        var trips = algorithm.plan(List.of(one, two), jobs, List.of(XIAYANG, INDUSTRIAL), START);

        assertThat(trips.stream().flatMap(trip -> trip.stops().stream())
            .filter(stop -> stop.type() == StopType.DROPOFF).map(DeliveryDispatchAlgorithm.Stop::requestId))
            .hasSize(12).doesNotHaveDuplicates();
        for (var trip : trips) {
            var stops = trip.stops();
            int firstDrop = stops.indexOf(stops.stream().filter(stop -> stop.type() == StopType.DROPOFF).findFirst().orElseThrow());
            assertThat(stops.subList(0, firstDrop)).allMatch(stop -> stop.type() == StopType.PICKUP);
            assertThat(stops.getLast().type()).isEqualTo(StopType.RETURN);
            for (int i = 1; i < stops.size(); i++) {
                assertThat(stops.get(i).arriveAt()).isAfterOrEqualTo(stops.get(i - 1).departAt());
            }
        }
    }

    @Test
    void noCartMeansNoTrip() {
        var job = new Job(1, 10, 1, "NORMAL", XIAYANG, 31.16, 121.12);
        assertThat(algorithm.plan(List.of(), List.of(job), List.of(XIAYANG), START)).isEmpty();
    }
}
