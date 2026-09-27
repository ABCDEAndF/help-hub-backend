package org.isolatedareas.helphub.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.isolatedareas.helphub.geo.ServiceArea;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/resident/profile")
public class ResidentProfileController {
    private final JdbcClient jdbc;
    private final ServiceArea area;

    public ResidentProfileController(JdbcClient jdbc, ServiceArea area) {
        this.jdbc = jdbc;
        this.area = area;
    }

    @GetMapping
    ProfileView get(@AuthenticationPrincipal Jwt jwt) {
        long userId = CurrentUser.id(jwt);
        assignDefaultLocation(userId);
        return find(userId);
    }

    /** Gives a resident without one a random default location in the service area, once. */
    void assignDefaultLocation(long userId) {
        double[] point = area.randomPoint();
        // Only the first assignment sticks, even when two screens ask at the same moment.
        jdbc.sql("""
                UPDATE users SET home_latitude=:lat, home_longitude=:lon
                WHERE id=:id AND role='RESIDENT' AND home_latitude IS NULL
                """).param("lat", point[0]).param("lon", point[1]).param("id", userId).update();
    }

    @PatchMapping
    @Transactional
    ProfileView update(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ProfileUpdate input) {
        long userId = CurrentUser.id(jwt);
        jdbc.sql("UPDATE users SET display_name=:name, household_size=:size WHERE id=:id AND role='RESIDENT'")
            .param("name", input.displayName().trim()).param("size", input.householdSize()).param("id", userId).update();
        return find(userId);
    }

    private ProfileView find(long userId) {
        return jdbc.sql("""
                SELECT id, display_name, household_size, locale, home_latitude, home_longitude FROM users WHERE id=:id
                """).param("id", userId).query((rs, n) -> new ProfileView(rs.getLong("id"),
                rs.getString("display_name"), rs.getInt("household_size"), rs.getString("locale"),
                rs.getBigDecimal("home_latitude") == null ? null : rs.getBigDecimal("home_latitude").doubleValue(),
                rs.getBigDecimal("home_longitude") == null ? null : rs.getBigDecimal("home_longitude").doubleValue()))
            .single();
    }

    public record ProfileUpdate(@NotBlank @Size(max = 100) String displayName,
                                @Min(1) @Max(20) int householdSize) {}
    /** defaultLatitude/Longitude: the resident's own random point in the service area. */
    public record ProfileView(long userId, String displayName, int householdSize, String locale,
                              Double defaultLatitude, Double defaultLongitude) {}
}
