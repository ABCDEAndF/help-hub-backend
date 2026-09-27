package org.isolatedareas.helphub.geo;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.RestClient;

/** Calls the real Tencent driving API with the production key (set TENCENT_MAP_KEY / TENCENT_MAP_SK). */
@EnabledIfEnvironmentVariable(named = "TENCENT_MAP_KEY", matches = ".+")
class RoadRouteLiveTest {
    @Test
    void fetchesADrivingRouteBetweenTheTwoServicePoints() throws Exception {
        var roads = new RoadRouteService(org.mockito.Mockito.mock(JdbcClient.class), new ObjectMapper(), RestClient.builder(),
            System.getenv("TENCENT_MAP_KEY"), System.getenv("TENCENT_MAP_SK"));
        var fetched = roads.fetch(31.15185, 121.12942, 31.18118, 121.09268);
        assertThat(fetched.path()).hasSizeGreaterThan(10);
        assertThat(fetched.distanceMeters()).isBetween(4000, 12000);
        assertThat(fetched.path().get(0)[0]).isBetween(31.14, 31.16);
    }
}
