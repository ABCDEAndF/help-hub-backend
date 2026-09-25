package org.isolatedareas.helphub.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class ConfirmationServiceTest {
    @Test
    void tokenIsScopedToItsOwnerAndCanOnlyBeConsumedOnce() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);

        ObjectMapper json = new ObjectMapper();
        ConfirmationService service = new ConfirmationService(redis, json);
        var arguments = json.createObjectNode().put("requestId", 42);
        var confirmation = service.create(7L, "reserve_pickup_slot", arguments, "Reserve one item");

        String ownerKey = "assistant-confirmation:7:" + confirmation.token();
        ArgumentCaptor<String> storedValue = ArgumentCaptor.forClass(String.class);
        verify(values).set(eq(ownerKey), storedValue.capture(), eq(Duration.ofMinutes(5)));

        String otherUserKey = "assistant-confirmation:8:" + confirmation.token();
        when(values.getAndDelete(otherUserKey)).thenReturn(null);
        assertThrows(IllegalArgumentException.class, () -> service.consume(8L, confirmation.token()));

        when(values.getAndDelete(ownerKey)).thenReturn(storedValue.getValue(), (String) null);
        var action = service.consume(7L, confirmation.token());
        assertEquals(7L, action.userId());
        assertEquals("reserve_pickup_slot", action.tool());
        assertEquals(42, action.arguments().path("requestId").asInt());
        assertThrows(IllegalArgumentException.class, () -> service.consume(7L, confirmation.token()));
        verify(values).getAndDelete(otherUserKey);
    }
}
