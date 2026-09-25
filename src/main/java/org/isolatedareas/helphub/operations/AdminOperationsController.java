package org.isolatedareas.helphub.operations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminOperationsController {
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public AdminOperationsController(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @GetMapping("/audit")
    List<AuditView> audit(@RequestParam(defaultValue = "100") int limit) {
        return jdbc.sql("""
                SELECT a.id, a.action, a.entity_type, a.entity_id, a.before_data, a.after_data,
                  a.correlation_id, a.occurred_at AS created_at, u.display_name
                FROM audit_log a LEFT JOIN users u ON u.id=a.actor_user_id
                ORDER BY a.occurred_at DESC LIMIT :limit
                """).param("limit", Math.min(Math.max(limit, 1), 500))
            .query((rs, n) -> new AuditView(rs.getLong("id"), rs.getString("display_name"),
                rs.getString("action"), rs.getString("entity_type"), rs.getString("entity_id"),
                parse(rs.getString("before_data")), parse(rs.getString("after_data")),
                rs.getString("correlation_id"), rs.getTimestamp("created_at").toInstant())).list();
    }

    @GetMapping("/notification-deliveries")
    List<DeliveryView> deliveries(@RequestParam(defaultValue = "100") int limit) {
        return jdbc.sql("""
                SELECT n.id, u.display_name, n.template_code, n.channel, n.status, n.attempts,
                  n.error_message, n.created_at, n.sent_at
                FROM notification_deliveries n JOIN users u ON u.id=n.recipient_user_id
                ORDER BY n.created_at DESC LIMIT :limit
                """).param("limit", Math.min(Math.max(limit, 1), 500))
            .query((rs, n) -> new DeliveryView(rs.getLong("id"), rs.getString("display_name"),
                rs.getString("template_code"), rs.getString("channel"), rs.getString("status"),
                rs.getInt("attempts"), rs.getString("error_message"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("sent_at") == null ? null : rs.getTimestamp("sent_at").toInstant())).list();
    }

    @GetMapping(value = "/impact/export", produces = "text/csv")
    ResponseEntity<byte[]> exportImpact() {
        StringBuilder csv = new StringBuilder("request_id,category,quantity,status,created_at,fulfilled_at,fulfillment_minutes\n");
        jdbc.sql("""
                SELECT id, category, quantity, status, created_at, fulfilled_at,
                  CASE WHEN fulfilled_at IS NULL THEN NULL ELSE TIMESTAMPDIFF(MINUTE, created_at, fulfilled_at) END AS minutes
                FROM supply_requests ORDER BY created_at
                """).query((rs, n) -> {
                csv.append(rs.getLong("id")).append(',').append(cell(rs.getString("category"))).append(',')
                    .append(rs.getInt("quantity")).append(',').append(cell(rs.getString("status"))).append(',')
                    .append(cell(rs.getTimestamp("created_at").toInstant().toString())).append(',')
                    .append(cell(rs.getTimestamp("fulfilled_at") == null ? "" : rs.getTimestamp("fulfilled_at").toInstant().toString())).append(',')
                    .append(rs.getObject("minutes") == null ? "" : rs.getLong("minutes")).append('\n');
                return 0;
            }).list();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("text", "csv", StandardCharsets.UTF_8));
        headers.setContentDisposition(ContentDisposition.attachment().filename("help-hub-impact.csv").build());
        return ResponseEntity.ok().headers(headers).body(csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    private JsonNode parse(String value) {
        if (value == null) return null;
        try { return json.readTree(value); }
        catch (Exception ignored) { return json.createObjectNode().put("raw", value); }
    }

    private String cell(String value) {
        if (value == null) return "";
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    public record AuditView(long id, String actor, String action, String entityType, String entityId,
                            JsonNode before, JsonNode after, String correlationId, Instant createdAt) {}
    public record DeliveryView(long id, String recipient, String template, String channel, String status,
                               int attempts, String error, Instant createdAt, Instant sentAt) {}
}
