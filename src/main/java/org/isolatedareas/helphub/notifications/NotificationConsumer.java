package org.isolatedareas.helphub.notifications;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.isolatedareas.helphub.config.RabbitConfig;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class NotificationConsumer {
    private static final String CONSUMER = "notification-v1";
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public NotificationConsumer(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @RabbitListener(queues = RabbitConfig.NOTIFICATION_QUEUE)
    @Transactional
    public void consume(org.springframework.amqp.core.Message message) throws IOException {
        String eventId = message.getMessageProperties().getMessageId();
        if (eventId == null) throw new IllegalArgumentException("Event has no message ID");
        int seen = jdbc.sql("""
                SELECT COUNT(*) FROM processed_messages WHERE event_id=:eventId AND consumer_name=:consumer
                """).param("eventId", eventId).param("consumer", CONSUMER).query(Integer.class).single();
        if (seen > 0) return;

        JsonNode payload = json.readTree(message.getBody());
        List<Long> recipients = recipients(payload);
        String template = payload.path("template").asText("GENERAL_UPDATE");
        for (long recipient : recipients) {
            jdbc.sql("""
                    INSERT IGNORE INTO notification_deliveries
                      (event_id, recipient_user_id, template_code, channel, status, payload)
                    VALUES (:eventId, :recipient, :template, 'IN_APP', 'SENT', :payload)
                    """)
                .param("eventId", eventId).param("recipient", recipient).param("template", template)
                .param("payload", new String(message.getBody(), java.nio.charset.StandardCharsets.UTF_8)).update();
            jdbc.sql("""
                    INSERT IGNORE INTO notification_deliveries
                      (event_id, recipient_user_id, template_code, channel, status, payload)
                    SELECT :eventId, id, :template, 'WECHAT', 'PENDING', :payload
                    FROM users WHERE id=:recipient AND wechat_open_id IS NOT NULL
                    """)
                .param("eventId", eventId).param("recipient", recipient).param("template", template)
                .param("payload", new String(message.getBody(), java.nio.charset.StandardCharsets.UTF_8)).update();
        }
        jdbc.sql("INSERT INTO processed_messages (event_id, consumer_name) VALUES (:eventId, :consumer)")
            .param("eventId", eventId).param("consumer", CONSUMER).update();
    }

    private List<Long> recipients(JsonNode payload) {
        if (payload.has("recipientUserId")) return List.of(payload.path("recipientUserId").asLong());
        if (payload.has("recipientRole")) {
            return jdbc.sql("SELECT id FROM users WHERE role=:role AND enabled=TRUE")
                .param("role", payload.path("recipientRole").asText()).query(Long.class).list();
        }
        return new ArrayList<>();
    }
}
