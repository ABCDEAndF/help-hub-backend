package org.isolatedareas.helphub.geo;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class GeoMathTest {
    @Test
    void returnsZeroForSamePointAndExpectedShanghaiDistance() {
        assertThat(GeoMath.distanceMeters(31.2304, 121.4737, 31.2304, 121.4737)).isZero();
        assertThat(GeoMath.distanceMeters(31.2304, 121.4737, 31.2204, 121.4737))
            .isBetween(1_100.0, 1_120.0);
    }
}
