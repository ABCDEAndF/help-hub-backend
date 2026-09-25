package org.isolatedareas.helphub.routing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import org.isolatedareas.helphub.config.RabbitConfig;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class RoutePlanningConsumer {
    private static final String CONSUMER = "route-planning-v1";
    private final RoutePlanningService routes;
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public RoutePlanningConsumer(RoutePlanningService routes, JdbcClient jdbc, ObjectMapper json) {
        this.routes = routes;
        this.jdbc = jdbc;
        this.json = json;
    }

    @RabbitListener(queues = RabbitConfig.ROUTE_QUEUE)
    public void consume(org.springframework.amqp.core.Message message) throws IOException {
        String eventId = message.getMessageProperties().getMessageId();
        if (eventId == null) throw new IllegalArgumentException("Event has no message ID");
        boolean processed = jdbc.sql("""
                SELECT COUNT(*) FROM processed_messages WHERE event_id=:eventId AND consumer_name=:consumer
                """).param("eventId", eventId).param("consumer", CONSUMER).query(Integer.class).single() > 0;
        if (processed) return;
        JsonNode payload = json.readTree(message.getBody());
        routes.compute(payload.path("routePlanId").asLong());
        jdbc.sql("INSERT IGNORE INTO processed_messages (event_id, consumer_name) VALUES (:eventId, :consumer)")
            .param("eventId", eventId).param("consumer", CONSUMER).update();
    }
}

