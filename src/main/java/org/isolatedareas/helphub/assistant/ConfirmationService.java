package org.isolatedareas.helphub.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class ConfirmationService {
    private static final long TTL_SECONDS = 300;
    private static final Duration TTL = Duration.ofSeconds(TTL_SECONDS);
    private final StringRedisTemplate redis;
    private final ObjectMapper json;

    public ConfirmationService(StringRedisTemplate redis, ObjectMapper json) {
        this.redis = redis;
        this.json = json;
    }

    public AssistantModels.Confirmation create(long userId, String tool, JsonNode arguments, String summary) {
        String token = UUID.randomUUID().toString();
        try {
            String payload = json.writeValueAsString(new PendingAction(userId, tool, arguments, summary));
            redis.opsForValue().set(key(userId, token), payload, TTL);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to create confirmation", error);
        }
        return new AssistantModels.Confirmation(token, summary, TTL_SECONDS);
    }

    public PendingAction consume(long userId, String token) {
        String payload = redis.opsForValue().getAndDelete(key(userId, token));
        if (payload == null) {
            throw new IllegalArgumentException("Confirmation expired or was already used");
        }
        try {
            return json.readValue(payload, PendingAction.class);
        } catch (Exception error) {
            throw new IllegalArgumentException("Confirmation is invalid", error);
        }
    }

    private String key(long userId, String token) {
        return "assistant-confirmation:" + userId + ":" + token;
    }

    public record PendingAction(long userId, String tool, JsonNode arguments, String summary) {
    }
}
