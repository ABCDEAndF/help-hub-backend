package org.isolatedareas.helphub.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class AuditService {
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public AuditService(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void record(Long actorId, String action, String entityType, Object entityId, Object before, Object after) {
        jdbc.sql("""
                INSERT INTO audit_log
                  (actor_user_id, action, entity_type, entity_id, before_data, after_data, correlation_id)
                VALUES (:actorId, :action, :entityType, :entityId, :beforeData, :afterData, :correlationId)
                """)
            .param("actorId", actorId)
            .param("action", action)
            .param("entityType", entityType)
            .param("entityId", String.valueOf(entityId))
            .param("beforeData", serialize(before))
            .param("afterData", serialize(after))
            .param("correlationId", UUID.randomUUID().toString())
            .update();
    }

    private String serialize(Object value) {
        if (value == null) return null;
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Audit value cannot be serialized", e);
        }
    }
}

