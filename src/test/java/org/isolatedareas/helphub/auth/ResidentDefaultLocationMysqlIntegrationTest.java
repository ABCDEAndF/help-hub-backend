package org.isolatedareas.helphub.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.isolatedareas.helphub.geo.GeoMath;
import org.isolatedareas.helphub.geo.ServiceArea;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.oauth2.jwt.Jwt;

/** Every resident gets their own random default location inside the service area (set REPRO_MYSQL_URL etc.). */
@EnabledIfEnvironmentVariable(named = "REPRO_MYSQL_URL", matches = ".+")
class ResidentDefaultLocationMysqlIntegrationTest {
    @Test
    void eachResidentGetsTheirOwnStableRandomPointInsideTheServiceArea() {
        var ds = new DriverManagerDataSource(System.getenv("REPRO_MYSQL_URL"),
            System.getenv("REPRO_MYSQL_USER"), System.getenv("REPRO_MYSQL_PASSWORD"));
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        var jdbc = JdbcClient.create(ds);
        var users = new UserRepository(jdbc);
        var profiles = new ResidentProfileController(jdbc, new ServiceArea(jdbc));
        List<double[]> points = jdbc.sql("SELECT latitude, longitude FROM service_points WHERE status='ACTIVE'")
            .query((rs, n) -> new double[] {rs.getDouble("latitude"), rs.getDouble("longitude")}).list();

        Set<String> seen = new HashSet<>();
        for (int index = 0; index < 50; index++) {
            UserAccount resident = users.upsertWechatUser("home-" + index, "社区居民");
            var first = profiles.get(jwt(resident.id()));
            assertThat(first.defaultLatitude()).isNotNull();
            assertThat(first.defaultLongitude()).isNotNull();
            double nearest = points.stream().mapToDouble(p -> GeoMath.distanceMeters(p[0], p[1],
                first.defaultLatitude(), first.defaultLongitude())).min().orElseThrow();
            assertThat(nearest).isLessThanOrEqualTo(ServiceArea.RADIUS_METERS + 1);
            assertThat(seen.add(first.defaultLatitude() + "," + first.defaultLongitude())).isTrue();
            // The point never changes once assigned.
            var again = profiles.get(jwt(resident.id()));
            assertThat(again.defaultLatitude()).isEqualTo(first.defaultLatitude());
            assertThat(again.defaultLongitude()).isEqualTo(first.defaultLongitude());
        }

        // Signing in again keeps the name the resident chose instead of the app's placeholder.
        UserAccount named = users.upsertWechatUser("home-0", "社区居民");
        profiles.update(jwt(named.id()), new ResidentProfileController.ProfileUpdate("王阿姨", 2));
        assertThat(users.upsertWechatUser("home-0", "社区居民").displayName()).isEqualTo("王阿姨");

        // Staff have no default location.
        UserAccount courier = users.upsertStaff("kd009", "快递员9", "$2a$10$courier");
        assertThat(profiles.get(jwt(courier.id())).defaultLatitude()).isNull();
    }

    private static Jwt jwt(long userId) {
        return Jwt.withTokenValue("test").header("alg", "none").subject(Long.toString(userId))
            .claim("roles", List.of("RESIDENT")).build();
    }
}
