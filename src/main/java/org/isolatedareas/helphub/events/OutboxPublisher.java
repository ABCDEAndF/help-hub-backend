package org.isolatedareas.helphub.events;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.isolatedareas.helphub.config.RabbitConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private final JdbcClient jdbc;
    private final RabbitTemplate rabbit;
    private final int batchSize;

    public OutboxPublisher(
        JdbcClient jdbc,
        RabbitTemplate rabbit,
        @Value("${app.outbox.batch-size}") int batchSize
    ) {
        this.jdbc = jdbc;
        this.rabbit = rabbit;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${app.outbox.publish-delay-ms}")
    public void publishPending() {
        List<OutboxRow> rows = jdbc.sql("""
                SELECT id, routing_key, payload, attempts
                FROM outbox_events
                WHERE status IN ('PENDING', 'FAILED') AND next_attempt_at <= CURRENT_TIMESTAMP(3)
                ORDER BY created_at LIMIT :limit
                """)
            .param("limit", batchSize)
            .query((rs, rowNum) -> new OutboxRow(
                rs.getString("id"), rs.getString("routing_key"), rs.getString("payload"), rs.getInt("attempts")))
            .list();
        for (OutboxRow row : rows) {
            try {
                Message message = MessageBuilder.withBody(row.payload().getBytes(java.nio.charset.StandardCharsets.UTF_8))
                    .setContentType("application/json")
                    .setMessageId(row.id())
                    .setHeader("eventId", row.id())
                    .build();
                CorrelationData correlation = new CorrelationData(row.id());
                rabbit.send(RabbitConfig.EVENTS_EXCHANGE, row.routingKey(), message, correlation);
                CorrelationData.Confirm confirmation = correlation.getFuture().get(5,
                    java.util.concurrent.TimeUnit.SECONDS);
                if (!confirmation.isAck()) {
                    throw new IllegalStateException("RabbitMQ rejected event: " + confirmation.getReason());
                }
                jdbc.sql("""
                        UPDATE outbox_events SET status='PUBLISHED', published_at=CURRENT_TIMESTAMP(3),
                          attempts=attempts+1, last_error=NULL WHERE id=:id
                        """)
                    .param("id", row.id()).update();
            } catch (Exception error) {
                int nextAttempts = row.attempts() + 1;
                Duration delay = Duration.ofSeconds(Math.min(300, 1L << Math.min(nextAttempts, 8)));
                jdbc.sql("""
                        UPDATE outbox_events SET status='FAILED', attempts=:attempts,
                          next_attempt_at=:nextAttempt, last_error=:error WHERE id=:id
                        """)
                    .param("attempts", nextAttempts)
                    .param("nextAttempt", Timestamp.from(Instant.now().plus(delay)))
                    .param("error", truncate(error.getMessage()))
                    .param("id", row.id())
                    .update();
                log.warn("Outbox event {} could not be published", row.id(), error);
            }
        }
    }

    private String truncate(String value) {
        if (value == null) return "Unknown publish error";
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }

    record OutboxRow(String id, String routingKey, String payload, int attempts) {
    }
}
