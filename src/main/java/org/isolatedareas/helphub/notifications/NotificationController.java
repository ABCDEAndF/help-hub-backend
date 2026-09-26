package org.isolatedareas.helphub.notifications;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.isolatedareas.helphub.auth.CurrentUser;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/resident/notifications")
public class NotificationController {
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public NotificationController(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @GetMapping
    List<NotificationView> list(@AuthenticationPrincipal Jwt jwt) {
        return jdbc.sql("""
                SELECT id, template_code, payload, created_at FROM notification_deliveries
                WHERE recipient_user_id=:userId AND channel='IN_APP' ORDER BY created_at DESC LIMIT 100
                """).param("userId", CurrentUser.id(jwt))
            .query((rs, n) -> new NotificationView(rs.getLong("id"), rs.getString("template_code"),
                parse(rs.getString("payload")), rs.getTimestamp("created_at").toInstant())).list();
    }

    private JsonNode parse(String value) {
        try {
            return json.readTree(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return json.createObjectNode().put("message", value);
        }
    }

    public record NotificationView(long id, String template, JsonNode payload, Instant createdAt) {
    }
}

