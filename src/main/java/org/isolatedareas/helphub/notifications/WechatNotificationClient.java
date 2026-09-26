package org.isolatedareas.helphub.notifications;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class WechatNotificationClient {
    private static final String TOKEN_KEY = "wechat:server-access-token";
    private final String appId;
    private final String appSecret;
    private final String templateId;
    private final String titleField;
    private final String statusField;
    private final String referenceField;
    private final String page;
    private final StringRedisTemplate redis;
    private final RestClient client;
    private final ObjectMapper json;

    public WechatNotificationClient(
        @Value("${app.wechat.app-id}") String appId,
        @Value("${app.wechat.app-secret}") String appSecret,
        @Value("${app.wechat.subscription.template-id:}") String templateId,
        @Value("${app.wechat.subscription.title-field:thing1}") String titleField,
        @Value("${app.wechat.subscription.status-field:phrase2}") String statusField,
        @Value("${app.wechat.subscription.reference-field:character_string3}") String referenceField,
        @Value("${app.wechat.subscription.page:pages/notifications/index}") String page,
        StringRedisTemplate redis, RestClient.Builder builder, ObjectMapper json
    ) {
        this.appId = appId;
        this.appSecret = appSecret;
        this.templateId = templateId;
        this.titleField = titleField;
        this.statusField = statusField;
        this.referenceField = referenceField;
        this.page = page;
        this.redis = redis;
        SimpleClientHttpRequestFactory requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(Duration.ofSeconds(5));
        requests.setReadTimeout(Duration.ofSeconds(10));
        this.client = builder.requestFactory(requests).baseUrl("https://api.weixin.qq.com").build();
        this.json = json;
    }

    public boolean configured() {
        return !appId.isBlank() && !appSecret.isBlank() && !templateId.isBlank();
    }

    public void send(String openId, String templateCode, JsonNode payload) {
        String reference = payload.has("requestId") ? "REQ-" + payload.path("requestId").asText()
            : payload.has("reservationId") ? "RES-" + payload.path("reservationId").asText()
            : payload.has("routePlanId") ? "ROUTE-" + payload.path("routePlanId").asText() : "UPDATE";
        ObjectNode data = json.createObjectNode();
        data.set(titleField, value(title(templateCode)));
        data.set(statusField, value(status(templateCode)));
        data.set(referenceField, value(reference));
        ObjectNode body = json.createObjectNode().put("touser", openId).put("template_id", templateId)
            .put("page", page).put("miniprogram_state", "formal").put("lang", "zh_CN");
        body.set("data", data);
        JsonNode response = client.post().uri(uri -> uri.path("/cgi-bin/message/subscribe/send")
                .queryParam("access_token", token()).build())
            .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(JsonNode.class);
        if (response == null || response.path("errcode").asInt(-1) != 0) {
            if (response != null && response.path("errcode").asInt() == 40014) redis.delete(TOKEN_KEY);
            throw new IllegalStateException("WeChat send failed: " + response);
        }
    }

    private String token() {
        String cached = redis.opsForValue().get(TOKEN_KEY);
        if (cached != null) return cached;
        JsonNode response = client.get().uri(uri -> uri.path("/cgi-bin/token")
                .queryParam("grant_type", "client_credential").queryParam("appid", appId)
                .queryParam("secret", appSecret).build())
            .retrieve().body(JsonNode.class);
        String token = response == null ? "" : response.path("access_token").asText();
        if (token.isBlank()) throw new IllegalStateException("WeChat token request failed: " + response);
        long expires = Math.max(60, response.path("expires_in").asLong(7200) - 300);
        redis.opsForValue().set(TOKEN_KEY, token, Duration.ofSeconds(expires));
        return token;
    }

    private ObjectNode value(String value) { return json.createObjectNode().put("value", value); }
    private String title(String code) {
        return switch (code) {
            case "REQUEST_RECEIVED", "REQUEST_CREATED" -> "物资需求已收到";
            case "REQUEST_UNDER_REVIEW", "REQUEST_APPROVED", "REQUEST_SCHEDULED", "REQUEST_FULFILLED",
                 "REQUEST_REJECTED", "REQUEST_CANCELLED", "REQUEST_STATUS_CHANGED" -> "物资需求进度更新";
            case "RESERVATION_HELD" -> "物资预约已创建";
            case "PAYMENT_SUCCEEDED" -> "微信支付已完成";
            case "PAYMENT_REFUNDED" -> "退款已完成";
            case "ROUTE_READY" -> "移动补给路线已生成";
            default -> "社区服务通知";
        };
    }
    private String status(String code) {
        return switch (code) {
            case "REQUEST_RECEIVED", "REQUEST_CREATED" -> "已提交";
            case "REQUEST_UNDER_REVIEW" -> "审核中";
            case "REQUEST_APPROVED" -> "已批准";
            case "REQUEST_SCHEDULED" -> "已安排";
            case "REQUEST_FULFILLED" -> "已完成";
            case "REQUEST_REJECTED" -> "未通过";
            case "REQUEST_CANCELLED" -> "已取消";
            case "REQUEST_STATUS_CHANGED" -> "已更新";
            case "RESERVATION_HELD" -> "待领取";
            case "PAYMENT_SUCCEEDED" -> "已支付";
            case "PAYMENT_REFUNDED" -> "已退款";
            case "ROUTE_READY" -> "已就绪";
            default -> "有更新";
        };
    }
}
