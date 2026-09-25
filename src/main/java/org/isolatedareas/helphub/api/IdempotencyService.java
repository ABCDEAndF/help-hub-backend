package org.isolatedareas.helphub.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Makes retried mobile mutations return the original result instead of creating duplicates. */
@Service
public class IdempotencyService {
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public IdempotencyService(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional
    public <T> T execute(String key, long userId, String operation, Object request, Class<T> responseType,
                         Supplier<T> action) {
        if (key == null || key.isBlank()) return action.get();
        if (!key.matches("[A-Za-z0-9._:-]{8,128}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Idempotency-Key");
        }
        String requestHash = hash(operation + "\n" + serialize(request));
        jdbc.sql("DELETE FROM idempotency_records WHERE idempotency_key=:key AND expires_at<CURRENT_TIMESTAMP(3)")
            .param("key", key).update();
        int inserted = jdbc.sql("""
                INSERT IGNORE INTO idempotency_records
                  (idempotency_key, user_id, request_hash, expires_at)
                VALUES (:key, :userId, :requestHash, :expiresAt)
                """).param("key", key).param("userId", userId).param("requestHash", requestHash)
            .param("expiresAt", Timestamp.from(Instant.now().plus(24, ChronoUnit.HOURS))).update();
        if (inserted == 0) {
            StoredResponse stored = jdbc.sql("""
                    SELECT user_id, request_hash, response_status, response_body
                    FROM idempotency_records WHERE idempotency_key=:key
                    """).param("key", key)
                .query((rs, n) -> new StoredResponse(rs.getLong("user_id"), rs.getString("request_hash"),
                    (Integer) rs.getObject("response_status"), rs.getString("response_body"))).single();
            if (stored.userId() != userId || !MessageDigest.isEqual(
                stored.requestHash().getBytes(StandardCharsets.UTF_8), requestHash.getBytes(StandardCharsets.UTF_8))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Idempotency-Key was already used for a different request");
            }
            if (stored.status() == null || stored.body() == null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "The original request is still processing");
            }
            return deserialize(stored.body(), responseType);
        }
        T response = action.get();
        jdbc.sql("""
                UPDATE idempotency_records SET response_status=200, response_body=:body
                WHERE idempotency_key=:key
                """).param("body", serialize(response)).param("key", key).update();
        return response;
    }

    private String serialize(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalArgumentException("Request cannot be serialized", e); }
    }

    private <T> T deserialize(String value, Class<T> type) {
        try { return json.readValue(value, type); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Stored response is invalid", e); }
    }

    private String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private record StoredResponse(long userId, String requestHash, Integer status, String body) {}
}
