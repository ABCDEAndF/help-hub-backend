package org.isolatedareas.helphub.geo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Driving routes along real roads from Tencent Location Service, whose coordinates (GCJ-02)
 * match the WeChat map. Each leg is fetched once and cached in road_legs; without a key, or
 * when the service fails, a leg falls back to the straight line between its ends.
 */
@Service
public class RoadRouteService {
    private static final Logger log = LoggerFactory.getLogger(RoadRouteService.class);
    private static final String DRIVING_PATH = "/ws/direction/v1/driving/";
    /** After a failure (quota, network) a leg is not asked for again for a while; screens refresh every 10 s. */
    private static final java.time.Duration RETRY_AFTER = java.time.Duration.ofMinutes(10);
    private final java.util.Map<String, java.time.Instant> failedUntil = new java.util.concurrent.ConcurrentHashMap<>();

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final RestClient client;
    private final String key;
    private final String secret;

    public RoadRouteService(JdbcClient jdbc, ObjectMapper json, RestClient.Builder builder,
                            @Value("${app.road-routing.tencent-key:}") String key,
                            @Value("${app.road-routing.tencent-secret:}") String secret) {
        SimpleClientHttpRequestFactory requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(java.time.Duration.ofSeconds(3));
        requests.setReadTimeout(java.time.Duration.ofSeconds(5));
        this.client = builder.requestFactory(requests).baseUrl("https://apis.map.qq.com").build();
        this.jdbc = jdbc;
        this.json = json;
        this.key = key == null ? "" : key.trim();
        this.secret = secret == null ? "" : secret.trim();
    }

    /** Points along the road from one place to another, both ends included. */
    public List<double[]> path(double fromLat, double fromLon, double toLat, double toLon) {
        List<double[]> straight = List.of(new double[] {fromLat, fromLon}, new double[] {toLat, toLon});
        if (key.isEmpty() || GeoMath.distanceMeters(fromLat, fromLon, toLat, toLon) < 30) return straight;
        String legKey = String.format(Locale.ROOT, "%.5f,%.5f>%.5f,%.5f", fromLat, fromLon, toLat, toLon);
        Optional<String> cached = jdbc.sql("SELECT path FROM road_legs WHERE leg_key=:key").param("key", legKey)
            .query(String.class).optional();
        try {
            if (cached.isPresent()) return parse(cached.get());
            java.time.Instant blocked = failedUntil.get(legKey);
            if (blocked != null && blocked.isAfter(java.time.Instant.now())) return straight;
            Fetched fetched = fetch(fromLat, fromLon, toLat, toLon);
            jdbc.sql("""
                    INSERT INTO road_legs (leg_key, distance_meters, duration_seconds, path)
                    VALUES (:key, :distance, :duration, :path)
                    ON DUPLICATE KEY UPDATE path = VALUES(path)
                    """).param("key", legKey).param("distance", fetched.distanceMeters())
                .param("duration", fetched.durationSeconds()).param("path", json.writeValueAsString(fetched.path()))
                .update();
            return fetched.path();
        } catch (Exception error) {
            failedUntil.put(legKey, java.time.Instant.now().plus(RETRY_AFTER));
            log.warn("Road route {} unavailable, drawing a straight line: {}", legKey, error.getMessage());
            return straight;
        }
    }

    Fetched fetch(double fromLat, double fromLon, double toLat, double toLon) throws Exception {
        String from = String.format(Locale.ROOT, "%.6f,%.6f", fromLat, fromLon);
        String to = String.format(Locale.ROOT, "%.6f,%.6f", toLat, toLon);
        // Parameters in ascending name order, as the signature requires.
        String query = "from=" + from + "&key=" + key + "&to=" + to;
        if (!secret.isEmpty()) query += "&sig=" + md5(DRIVING_PATH + "?" + query + secret);
        JsonNode body = json.readTree(client.get().uri(DRIVING_PATH + "?" + query).retrieve().body(String.class));
        if (body.path("status").asInt(-1) != 0) {
            throw new IllegalStateException("status " + body.path("status").asInt() + " " + body.path("message").asText());
        }
        JsonNode route = body.path("result").path("routes").path(0);
        List<double[]> path = decode(route.path("polyline"));
        if (path.size() < 2) throw new IllegalStateException("empty polyline");
        return new Fetched(path, route.path("distance").asInt(), route.path("duration").asInt() * 60);
    }

    /** Tencent polylines are [lat, lon, dLat*1e6, dLon*1e6, ...]: every value after the first pair is a delta. */
    static List<double[]> decode(JsonNode polyline) {
        double[] coords = new double[polyline.size()];
        for (int index = 0; index < coords.length; index++) {
            coords[index] = index < 2 ? polyline.get(index).asDouble()
                : coords[index - 2] + polyline.get(index).asDouble() / 1_000_000;
        }
        List<double[]> points = new ArrayList<>();
        for (int index = 0; index + 1 < coords.length; index += 2) points.add(new double[] {coords[index], coords[index + 1]});
        return points;
    }

    /** The point a fraction of the way along a path, measured by distance. */
    public static double[] along(List<double[]> path, double fraction) {
        double total = 0;
        for (int index = 1; index < path.size(); index++) total += segment(path, index);
        double target = Math.max(0, Math.min(1, fraction)) * total;
        for (int index = 1; index < path.size(); index++) {
            double length = segment(path, index);
            if (target <= length && length > 0) {
                double t = target / length;
                double[] a = path.get(index - 1);
                double[] b = path.get(index);
                return new double[] {a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t};
            }
            target -= length;
        }
        return path.get(path.size() - 1);
    }

    private static double segment(List<double[]> path, int index) {
        double[] a = path.get(index - 1);
        double[] b = path.get(index);
        return GeoMath.distanceMeters(a[0], a[1], b[0], b[1]);
    }

    private List<double[]> parse(String stored) throws Exception {
        return List.of(json.readValue(stored, double[][].class));
    }

    private static String md5(String value) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    record Fetched(List<double[]> path, int distanceMeters, int durationSeconds) {
    }
}
