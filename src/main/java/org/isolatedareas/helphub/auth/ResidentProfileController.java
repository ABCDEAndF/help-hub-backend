package org.isolatedareas.helphub.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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

    public ResidentProfileController(JdbcClient jdbc) { this.jdbc = jdbc; }

    @GetMapping
    ProfileView get(@AuthenticationPrincipal Jwt jwt) {
        return find(CurrentUser.id(jwt));
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
        return jdbc.sql("SELECT id, display_name, household_size, locale FROM users WHERE id=:id")
            .param("id", userId).query((rs, n) -> new ProfileView(rs.getLong("id"),
                rs.getString("display_name"), rs.getInt("household_size"), rs.getString("locale"))).single();
    }

    public record ProfileUpdate(@NotBlank @Size(max = 100) String displayName,
                                @Min(1) @Max(20) int householdSize) {}
    public record ProfileView(long userId, String displayName, int householdSize, String locale) {}
}
