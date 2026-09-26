package org.isolatedareas.helphub.automation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class TripProgressTest {
    private static final Instant T0 = Instant.parse("2026-09-26T02:00:00Z");
    private static final List<TripProgress.TimedStop> STOPS = List.of(
        new TripProgress.TimedStop(31.10, 121.10, T0.plusSeconds(100), T0.plusSeconds(400)),
        new TripProgress.TimedStop(31.20, 121.20, T0.plusSeconds(500), T0.plusSeconds(680)));

    @Test
    void movesAlongALegInProportionToElapsedTime() {
        var halfway = TripProgress.at(31.00, 121.00, T0, STOPS, T0.plusSeconds(50));
        assertThat(halfway.latitude()).isCloseTo(31.05, within(1e-9));
        assertThat(halfway.longitude()).isCloseTo(121.05, within(1e-9));
        assertThat(halfway.nextStopIndex()).isZero();
        assertThat(halfway.atStop()).isFalse();
    }

    @Test
    void staysAtAStopWhileLoading() {
        var loading = TripProgress.at(31.00, 121.00, T0, STOPS, T0.plusSeconds(250));
        assertThat(loading.latitude()).isEqualTo(31.10);
        assertThat(loading.atStop()).isTrue();
        assertThat(loading.nextStopIndex()).isZero();
    }

    @Test
    void resumesFromTheStopItLeft() {
        var second = TripProgress.at(31.00, 121.00, T0, STOPS, T0.plusSeconds(450));
        assertThat(second.latitude()).isCloseTo(31.15, within(1e-9));
        assertThat(second.nextStopIndex()).isEqualTo(1);
    }

    @Test
    void endsAtTheLastStop() {
        var done = TripProgress.at(31.00, 121.00, T0, STOPS, T0.plusSeconds(10_000));
        assertThat(done.latitude()).isEqualTo(31.20);
        assertThat(done.nextStopIndex()).isEqualTo(2);
    }
}
