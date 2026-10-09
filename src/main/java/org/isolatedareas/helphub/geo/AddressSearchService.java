package org.isolatedareas.helphub.geo;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Turns an address a resident types ("青湖路 88 弄 5 号", "绿地小区") into points on the WeChat map.
 * The lookup runs here on the server through a map provider's web service — Tencent Location
 * Service, or AMap when only an AMap key is configured — because WeChat does not grant location
 * APIs to a personally registered mini program, while either provider issues keys to individual
 * developers. Both return GCJ-02, the WeChat map's own coordinates. Place suggestions answer
 * estate and building names; when they find nothing the text is geocoded as a street address.
 * Answers are cached for a day so repeated searches do not spend the key's daily quota.
 */
@Service
public class AddressSearchService {
    private static final Logger log = LoggerFactory.getLogger(AddressSearchService.class);
    private static final String SUGGESTION_PATH = "/ws/place/v1/suggestion";
    private static final String GEOCODER_PATH = "/ws/geocoder/v1/";
    private static final String CITY = "上海市";
    private static final Duration CACHE_TTL = Duration.ofDays(1);
    // The backend accepts only coordinates in this box (CreateSupplyRequest); never offer others.
    private static final double MIN_LAT = 30, MAX_LAT = 32, MIN_LON = 120, MAX_LON = 123;

    private final RestClient client;
    private final ObjectMapper json;
    private final StringRedisTemplate redis;
    private final String key;
    private final String secret;
    private final String amapKey;
    private final String amapSecret;

    public AddressSearchService(RestClient.Builder builder, ObjectMapper json, StringRedisTemplate redis,
                                @Value("${app.road-routing.tencent-key:}") String key,
                                @Value("${app.road-routing.tencent-secret:}") String secret,
                                @Value("${app.address-search.amap-key:}") String amapKey,
                                @Value("${app.address-search.amap-secret:}") String amapSecret) {
        SimpleClientHttpRequestFactory requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(Duration.ofSeconds(3));
        requests.setReadTimeout(Duration.ofSeconds(5));
        this.client = builder.requestFactory(requests).build();
        this.json = json;
        this.redis = redis;
        this.key = key == null ? "" : key.trim();
        this.secret = secret == null ? "" : secret.trim();
        this.amapKey = amapKey == null ? "" : amapKey.trim();
        this.amapSecret = amapSecret == null ? "" : amapSecret.trim();
    }

    public boolean available() {
        return !key.isEmpty() || !amapKey.isEmpty();
    }

    /** Places matching the text inside the service city, best match first. */
    public List<Place> search(String text) {
        String keyword = text == null ? "" : text.trim().replaceAll("\\s+", " ");
        if (!available() || keyword.length() < 2) return List.of();
        String cacheKey = "place-search:" + keyword;
        List<Place> cached = cached(cacheKey);
        if (cached != null) return cached;
        boolean tencent = !key.isEmpty();
        List<Place> places = new ArrayList<>(tencent ? suggestions(keyword) : amapTips(keyword));
        if (places.isEmpty()) places.addAll(tencent ? geocode(keyword) : amapGeocode(keyword));
        try {
            redis.opsForValue().set(cacheKey, json.writeValueAsString(places), CACHE_TTL);
        } catch (Exception ignored) {
            // Searching still works without the cache.
        }
        return places;
    }

    private List<Place> suggestions(String keyword) {
        Map<String, String> params = new TreeMap<>();
        params.put("keyword", keyword);
        params.put("region", CITY);
        params.put("region_fix", "1");
        // Policy 1 ranks homes, estates and addresses first, as for a delivery address.
        params.put("policy", "1");
        params.put("page_size", "10");
        JsonNode body = call(SUGGESTION_PATH, params);
        List<Place> places = new ArrayList<>();
        if (body == null) return places;
        for (JsonNode item : body.path("data")) {
            place(item.path("title").asText(""), item.path("address").asText(""),
                item.path("location").path("lat").asDouble(Double.NaN), item.path("location").path("lng").asDouble(Double.NaN), places);
        }
        return places;
    }

    private List<Place> geocode(String keyword) {
        Map<String, String> params = new TreeMap<>();
        params.put("address", keyword.startsWith(CITY) ? keyword : CITY + keyword);
        JsonNode body = call(GEOCODER_PATH, params);
        List<Place> places = new ArrayList<>();
        if (body == null) return places;
        JsonNode result = body.path("result");
        JsonNode parts = result.path("address_components");
        String address = parts.path("province").asText("") + parts.path("city").asText("")
            + parts.path("district").asText("") + parts.path("street").asText("") + parts.path("street_number").asText("");
        place(result.path("title").asText(keyword), address,
            result.path("location").path("lat").asDouble(Double.NaN), result.path("location").path("lng").asDouble(Double.NaN), places);
        return places;
    }

    private List<Place> amapTips(String keyword) {
        Map<String, String> params = new TreeMap<>();
        params.put("keywords", keyword);
        params.put("city", CITY);
        params.put("citylimit", "true");
        JsonNode body = amap("/v3/assistant/inputtips", params);
        List<Place> places = new ArrayList<>();
        if (body == null) return places;
        for (JsonNode tip : body.path("tips")) {
            // Bus lines and bare keywords come back without a point ("location": []).
            double[] point = amapPoint(tip.path("location"));
            if (point == null) continue;
            String address = tip.path("address").isTextual() ? tip.path("address").asText() : "";
            place(tip.path("name").asText(""), tip.path("district").asText("") + address, point[0], point[1], places);
        }
        return places;
    }

    private List<Place> amapGeocode(String keyword) {
        Map<String, String> params = new TreeMap<>();
        params.put("address", keyword.startsWith(CITY) ? keyword : CITY + keyword);
        params.put("city", CITY);
        JsonNode body = amap("/v3/geocode/geo", params);
        List<Place> places = new ArrayList<>();
        if (body == null) return places;
        for (JsonNode code : body.path("geocodes")) {
            double[] point = amapPoint(code.path("location"));
            String address = code.path("formatted_address").asText("");
            if (point != null) place(address.isBlank() ? keyword : address, address, point[0], point[1], places);
        }
        return places;
    }

    /** AMap writes points as "longitude,latitude". */
    static double[] amapPoint(JsonNode location) {
        if (!location.isTextual() || !location.asText().contains(",")) return null;
        String[] parts = location.asText().split(",");
        try {
            return new double[] {Double.parseDouble(parts[1]), Double.parseDouble(parts[0])};
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    /** AMap signs the name-ordered raw parameters followed by the private key. */
    private JsonNode amap(String path, Map<String, String> params) {
        Map<String, String> signed = new TreeMap<>(params);
        signed.put("key", amapKey);
        String raw = query(signed, false);
        String encoded = query(signed, true);
        if (!amapSecret.isEmpty()) encoded += "&sig=" + md5(raw + amapSecret);
        try {
            JsonNode body = json.readTree(client.get().uri(URI.create("https://restapi.amap.com" + path + "?" + encoded))
                .retrieve().body(String.class));
            if (!"1".equals(body.path("status").asText())) {
                log.warn("Address search {} failed: {} {}", path, body.path("infocode").asText(), body.path("info").asText());
                return null;
            }
            return body;
        } catch (Exception error) {
            log.warn("Address search {} unavailable: {}", path, error.getMessage());
            return null;
        }
    }

    /** name=value pairs in the map's order; encoded for the request, raw for the signature. */
    static String query(Map<String, String> params, boolean encode) {
        StringBuilder query = new StringBuilder();
        params.forEach((name, value) -> {
            if (!query.isEmpty()) query.append('&');
            query.append(name).append('=')
                .append(encode ? URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20") : value);
        });
        return query.toString();
    }

    private void place(String title, String address, double latitude, double longitude, List<Place> into) {
        if (title.isBlank() || !(latitude >= MIN_LAT && latitude <= MAX_LAT && longitude >= MIN_LON && longitude <= MAX_LON)) return;
        into.add(new Place(title.trim(), address.trim(), round(latitude), round(longitude)));
    }

    /** Signed request (parameters in name order, raw values, then the secret); null when the service fails. */
    private JsonNode call(String path, Map<String, String> params) {
        Map<String, String> signed = new TreeMap<>(params);
        signed.put("key", key);
        String raw = query(signed, false);
        String encoded = query(signed, true);
        if (!secret.isEmpty()) encoded += "&sig=" + md5(path + "?" + raw + secret);
        try {
            JsonNode body = json.readTree(client.get().uri(URI.create("https://apis.map.qq.com" + path + "?" + encoded))
                .retrieve().body(String.class));
            if (body.path("status").asInt(-1) != 0) {
                log.warn("Address search {} failed: status {} {}", path, body.path("status").asInt(), body.path("message").asText());
                return null;
            }
            return body;
        } catch (Exception error) {
            log.warn("Address search {} unavailable: {}", path, error.getMessage());
            return null;
        }
    }

    private List<Place> cached(String cacheKey) {
        try {
            String stored = redis.opsForValue().get(cacheKey);
            return stored == null ? null : json.readValue(stored, new TypeReference<List<Place>>() { });
        } catch (Exception ignored) {
            return null;
        }
    }

    private static double round(double degrees) {
        return Math.round(degrees * 1_000_000) / 1_000_000.0;
    }

    private static String md5(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public record Place(String title, String address, double latitude, double longitude) {
    }
}
