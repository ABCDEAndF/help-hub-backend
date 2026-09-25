package org.isolatedareas.helphub.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class WechatAuthServiceTest {
    private MockRestServiceServer server;
    private WechatAuthService service;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        service = new WechatAuthService("test-app-id", "test-secret", builder, new ObjectMapper());
    }

    @Test
    void acceptsJsonBodyWhenWechatLabelsItTextPlain() {
        server.expect(requestTo("https://api.weixin.qq.com/sns/jscode2session"
                + "?appid=test-app-id&secret=test-secret&js_code=login-code&grant_type=authorization_code"))
            .andRespond(withSuccess("{\"openid\":\"openid-123\",\"session_key\":\"key\"}", MediaType.TEXT_PLAIN));

        assertThat(service.exchangeCode("login-code")).isEqualTo("openid-123");
        server.verify();
    }

    @Test
    void reportsInvalidNonJsonResponseAsBadGateway() {
        server.expect(requestTo("https://api.weixin.qq.com/sns/jscode2session"
                + "?appid=test-app-id&secret=test-secret&js_code=login-code&grant_type=authorization_code"))
            .andRespond(withSuccess("upstream unavailable", MediaType.TEXT_PLAIN));

        assertThatThrownBy(() -> service.exchangeCode("login-code"))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY));
        server.verify();
    }
}
