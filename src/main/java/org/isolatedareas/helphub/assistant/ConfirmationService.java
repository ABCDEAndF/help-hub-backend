package org.isolatedareas.helphub.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Actions the assistant has prepared and the resident has yet to agree to. Each has a token; the
 * action waiting in a conversation is also remembered there, so the resident can agree by replying
 * "确认" in the chat. Address search results are kept per conversation for "第二个".
 */
@Service
public class ConfirmationService {
    private static final long TTL_SECONDS = 300;
    private static final Duration TTL = Duration.ofSeconds(TTL_SECONDS);
    private static final Duration PLACES_TTL = Duration.ofMinutes(15);
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
        return read(payload);
    }

    /** The action behind a token without using it up, or null when it has expired or been used. */
    public PendingAction peek(long userId, String token) {
        String payload = redis.opsForValue().get(key(userId, token));
        return payload == null ? null : read(payload);
    }

    /** Drops an action the resident declined. */
    public void discard(long userId, String token) {
        redis.delete(key(userId, token));
    }

    /** Remembers the action now waiting for the resident's answer in this conversation. */
    public void await(long userId, String conversationId, String token) {
        redis.opsForValue().set(waitingKey(userId, conversationId), token, TTL);
    }

    /** The token of the action waiting in this conversation, or null. */
    public String waiting(long userId, String conversationId) {
        return redis.opsForValue().get(waitingKey(userId, conversationId));
    }

    public void stopWaiting(long userId, String conversationId) {
        redis.delete(waitingKey(userId, conversationId));
    }

    /** The places found for the resident's last address search in this conversation. */
    public void rememberPlaces(long userId, String conversationId, JsonNode places) {
        try {
            redis.opsForValue().set(placesKey(userId, conversationId), json.writeValueAsString(places), PLACES_TTL);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to remember the places found", error);
        }
    }

    public JsonNode places(long userId, String conversationId) {
        String payload = redis.opsForValue().get(placesKey(userId, conversationId));
        try {
            return payload == null ? null : json.readTree(payload);
        } catch (Exception error) {
            return null;
        }
    }

    private PendingAction read(String payload) {
        try {
            return json.readValue(payload, PendingAction.class);
        } catch (Exception error) {
            throw new IllegalArgumentException("Confirmation is invalid", error);
        }
    }

    private String key(long userId, String token) {
        return "assistant-confirmation:" + userId + ":" + token;
    }

    private String waitingKey(long userId, String conversationId) {
        return "assistant-waiting:" + userId + ":" + conversationId;
    }

    private String placesKey(long userId, String conversationId) {
        return "assistant-places:" + userId + ":" + conversationId;
    }

    public record PendingAction(long userId, String tool, JsonNode arguments, String summary) {
    }
}
