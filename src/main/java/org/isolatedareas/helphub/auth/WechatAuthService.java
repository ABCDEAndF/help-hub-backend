package org.isolatedareas.helphub.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
public class WechatAuthService {
    private final String appId;
    private final String appSecret;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public WechatAuthService(
        @Value("${app.wechat.app-id}") String appId,
        @Value("${app.wechat.app-secret}") String appSecret,
        RestClient.Builder builder,
        ObjectMapper objectMapper
    ) {
        this.appId = appId;
        this.appSecret = appSecret;
        this.restClient = builder.baseUrl("https://api.weixin.qq.com").build();
        this.objectMapper = objectMapper;
    }

    public String exchangeCode(String code) {
        if (appId.isBlank() || appSecret.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "WeChat credentials are not configured");
        }
        String body;
        try {
            // WeChat occasionally returns a JSON body labelled text/plain. Read
            // the payload as text first so a wrong Content-Type cannot break login.
            body = restClient.get()
                .uri(uri -> uri.path("/sns/jscode2session")
                    .queryParam("appid", appId)
                    .queryParam("secret", appSecret)
                    .queryParam("js_code", code)
                    .queryParam("grant_type", "authorization_code")
                    .build())
                .retrieve()
                .body(String.class);
        } catch (RestClientException error) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Unable to contact WeChat login service", error);
        }

        CodeSession response;
        try {
            response = body == null ? null : objectMapper.readValue(body, CodeSession.class);
        } catch (JsonProcessingException error) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "WeChat login service returned an invalid response", error);
        }
        if (response == null || response.openId() == null || response.errorCode() != null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                response == null || response.errorMessage() == null || response.errorMessage().isBlank()
                    ? "WeChat login failed"
                    : response.errorMessage());
        }
        return response.openId();
    }

    record CodeSession(
        @JsonProperty("openid") String openId,
        @JsonProperty("session_key") String sessionKey,
        @JsonProperty("unionid") String unionId,
        @JsonProperty("errcode") Integer errorCode,
        @JsonProperty("errmsg") String errorMessage
    ) {
    }
}
