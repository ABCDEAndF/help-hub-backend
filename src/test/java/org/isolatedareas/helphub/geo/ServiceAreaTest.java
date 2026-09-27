package org.isolatedareas.helphub.geo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class ServiceAreaTest {
    @Test
    void offsetsByTheRequestedDistanceInEveryDirection() {
        for (double bearing = 0; bearing < 2 * Math.PI; bearing += Math.PI / 8) {
            double[] point = ServiceArea.offset(31.15185, 121.12942, 3000, bearing);
            assertThat(GeoMath.distanceMeters(31.15185, 121.12942, point[0], point[1])).isCloseTo(3000, within(15.0));
        }
    }
}
