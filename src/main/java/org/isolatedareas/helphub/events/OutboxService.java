package org.isolatedareas.helphub.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class OutboxService {
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public OutboxService(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public String append(String aggregateType, Object aggregateId, String eventType, String routingKey, Object payload) {
        String id = UUID.randomUUID().toString();
        try {
            jdbc.sql("""
                    INSERT INTO outbox_events
                      (id, aggregate_type, aggregate_id, event_type, routing_key, payload)
                    VALUES (:id, :aggregateType, :aggregateId, :eventType, :routingKey, :payload)
                    """)
                .param("id", id)
                .param("aggregateType", aggregateType)
                .param("aggregateId", String.valueOf(aggregateId))
                .param("eventType", eventType)
                .param("routingKey", routingKey)
                .param("payload", objectMapper.writeValueAsString(payload))
                .update();
            return id;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Event payload cannot be serialized", e);
        }
    }
}

