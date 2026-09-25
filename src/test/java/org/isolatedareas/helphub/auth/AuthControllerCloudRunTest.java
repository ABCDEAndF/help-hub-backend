package org.isolatedareas.helphub.auth;

import java.time.Instant;
import org.isolatedareas.helphub.domain.Role;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthControllerCloudRunTest {
    private static final String APP_ID = "wx8e6d63e949e813c8";
    private static final String CLOUD_ENV = "prod-d7gx7nh5z59528a42";

    private final UserRepository users = mock(UserRepository.class);
    private final JwtService tokens = mock(JwtService.class);
    private final WechatAuthService wechat = mock(WechatAuthService.class);
    private final AuthController controller = new AuthController(
        users, tokens, wechat, false, APP_ID, CLOUD_ENV, "13800000000", "Strong-test-password");

    @Test
    void issuesTokenForIdentityInjectedByCloudRun() {
        UserAccount user = new UserAccount(42L, null, "社区居民", Role.RESIDENT, true);
        JwtService.TokenResponse expected = new JwtService.TokenResponse(
            "token", Instant.parse("2030-01-01T00:00:00Z"), 42L, "社区居民", "RESIDENT");
        when(users.upsertWechatUser("openid-123", "社区居民")).thenReturn(user);
        when(tokens.issue(user)).thenReturn(expected);

        JwtService.TokenResponse actual = controller.cloudRunLogin(
            "openid-123", APP_ID, CLOUD_ENV, new AuthController.CloudRunLoginRequest("社区居民"));

        assertThat(actual).isEqualTo(expected);
        verify(users).upsertWechatUser("openid-123", "社区居民");
    }

    @Test
    void rejectsIdentityWithoutExpectedAppIdAndEnvironment() {
        assertThatThrownBy(() -> controller.cloudRunLogin(
            "openid-123", "wrong-app", CLOUD_ENV, null))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> controller.cloudRunLogin(
            "openid-123", APP_ID, "wrong-env", null))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }
}
