package org.isolatedareas.helphub.notifications;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class WechatNotificationDispatcher {
    private static final Logger log = LoggerFactory.getLogger(WechatNotificationDispatcher.class);
    private final JdbcClient jdbc;
    private final WechatNotificationClient wechat;
    private final ObjectMapper json;

    public WechatNotificationDispatcher(JdbcClient jdbc, WechatNotificationClient wechat, ObjectMapper json) {
        this.jdbc = jdbc;
        this.wechat = wechat;
        this.json = json;
    }

    @Scheduled(fixedDelay = 2_000)
    public void dispatch() {
        if (!wechat.configured()) return;
        List<Delivery> rows = jdbc.sql("""
                SELECT n.id, n.template_code, n.payload, n.attempts, u.wechat_open_id
                FROM notification_deliveries n JOIN users u ON u.id=n.recipient_user_id
                WHERE n.channel='WECHAT' AND n.status IN ('PENDING','FAILED')
                  AND n.next_attempt_at<=CURRENT_TIMESTAMP(3) AND n.attempts<8
                  AND u.wechat_open_id IS NOT NULL
                ORDER BY n.created_at LIMIT 50
                """).query((rs, n) -> new Delivery(rs.getLong("id"), rs.getString("template_code"),
                rs.getString("payload"), rs.getInt("attempts"), rs.getString("wechat_open_id"))).list();
        for (Delivery row : rows) {
            try {
                JsonNode payload = json.readTree(row.payload());
                wechat.send(row.openId(), row.templateCode(), payload);
                jdbc.sql("""
                        UPDATE notification_deliveries SET status='SENT', attempts=attempts+1,
                          sent_at=CURRENT_TIMESTAMP(3), error_message=NULL WHERE id=:id
                        """).param("id", row.id()).update();
            } catch (Exception error) {
                int attempts = row.attempts() + 1;
                long delay = Math.min(900, 1L << Math.min(attempts, 9));
                jdbc.sql("""
                        UPDATE notification_deliveries SET status='FAILED', attempts=:attempts,
                          next_attempt_at=:nextAttempt, error_message=:error WHERE id=:id
                        """).param("attempts", attempts).param("nextAttempt", Timestamp.from(Instant.now().plusSeconds(delay)))
                    .param("error", truncate(error.getMessage())).param("id", row.id()).update();
                log.warn("WeChat notification {} failed", row.id(), error);
            }
        }
    }

    private String truncate(String value) {
        if (value == null) return "Unknown WeChat notification error";
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }
    private record Delivery(long id, String templateCode, String payload, int attempts, String openId) {}
}
