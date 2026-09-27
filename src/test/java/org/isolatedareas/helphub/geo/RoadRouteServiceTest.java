package org.isolatedareas.helphub.geo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class RoadRouteServiceTest {
    @Test
    void decodesTencentDeltaPolyline() throws Exception {
        var polyline = new ObjectMapper().readTree("[31.15185, 121.12942, 1000, -2000, 500, 0]");
        List<double[]> points = RoadRouteService.decode(polyline);
        assertThat(points).hasSize(3);
        assertThat(points.get(1)[0]).isCloseTo(31.15285, within(1e-9));
        assertThat(points.get(1)[1]).isCloseTo(121.12742, within(1e-9));
        assertThat(points.get(2)[0]).isCloseTo(31.15335, within(1e-9));
        assertThat(points.get(2)[1]).isCloseTo(121.12742, within(1e-9));
    }

    @Test
    void placesAPointByDistanceAlongTheRoad() {
        // An L-shaped road: 1 km north, then 1 km east (approximately).
        List<double[]> path = List.of(new double[] {31.0, 121.0}, new double[] {31.009, 121.0}, new double[] {31.009, 121.0105});
        assertThat(RoadRouteService.along(path, 0)).containsExactly(31.0, 121.0);
        assertThat(RoadRouteService.along(path, 1)).containsExactly(31.009, 121.0105);
        double[] quarter = RoadRouteService.along(path, 0.25);
        assertThat(quarter[1]).isEqualTo(121.0);
        assertThat(quarter[0]).isCloseTo(31.0045, within(2e-4));
        double[] threeQuarters = RoadRouteService.along(path, 0.75);
        assertThat(threeQuarters[0]).isEqualTo(31.009);
        assertThat(threeQuarters[1]).isCloseTo(121.00525, within(3e-4));
    }
}
