package org.isolatedareas.helphub.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import org.isolatedareas.helphub.domain.Role;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class OperatorLoginTest {
    private final UserRepository users = mock(UserRepository.class);
    private final JwtService tokens = mock(JwtService.class);
    private final AuthController controller = new AuthController(users, tokens, mock(WechatAuthService.class),
        false, "appid", "env", "13800000000", "Strong-test-password");

    @Test
    void issuesAdminTokenOnlyForExactConfiguredCredentials() {
        var account = new UserAccount(1, "13800000000", "运营管理员", Role.ADMIN, true);
        var token = new JwtService.TokenResponse("token", Instant.EPOCH, 1, "运营管理员", "ADMIN");
        when(users.findByPhone("13800000000")).thenReturn(Optional.of(account));
        when(tokens.issue(account)).thenReturn(token);

        assertThat(controller.operatorLogin(
            new AuthController.OperatorLoginRequest("13800000000", "Strong-test-password")))
            .isEqualTo(token);
    }

    @Test
    void rejectsWrongPasswordAndResidentRole() {
        assertThatThrownBy(() -> controller.operatorLogin(
            new AuthController.OperatorLoginRequest("13800000000", "wrong")))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));

        when(users.findByPhone("13800000000")).thenReturn(Optional.of(
            new UserAccount(2, "13800000000", "居民", Role.RESIDENT, true)));
        assertThatThrownBy(() -> controller.operatorLogin(
            new AuthController.OperatorLoginRequest("13800000000", "Strong-test-password")))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }
}
