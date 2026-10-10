package org.isolatedareas.helphub.auth;

import java.time.Instant;
import org.isolatedareas.helphub.domain.Role;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebAuthControllerTest {
    private static final String KEY = "Zm9vYmFyLWRldmljZS1rZXktMDEyMzQ1Njc4OWFiY2RlZg";

    private final UserRepository users = mock(UserRepository.class);
    private final JwtService tokens = mock(JwtService.class);

    @Test
    void storesOnlyTheHashOfTheDeviceKey() {
        UserAccount user = new UserAccount(7L, null, "社区居民", Role.RESIDENT, true);
        JwtService.TokenResponse expected = new JwtService.TokenResponse(
            "token", Instant.parse("2030-01-01T00:00:00Z"), 7L, "社区居民", "RESIDENT");
        String hash = WebAuthController.sha256(KEY);
        when(users.upsertWebUser(hash, "社区居民")).thenReturn(user);
        when(tokens.issue(user)).thenReturn(expected);

        JwtService.TokenResponse actual = new WebAuthController(users, tokens, true)
            .webLogin(new WebAuthController.WebLoginRequest(KEY, "社区居民"));

        assertThat(actual).isEqualTo(expected);
        assertThat(hash).hasSize(64).doesNotContain(KEY);
        verify(users).upsertWebUser(hash, "社区居民");
    }

    @Test
    void sameKeyAlwaysMapsToTheSameResident() {
        assertThat(WebAuthController.sha256(KEY)).isEqualTo(WebAuthController.sha256(KEY));
        assertThat(WebAuthController.sha256(KEY)).isNotEqualTo(WebAuthController.sha256(KEY + "x"));
    }

    @Test
    void rejectsShortOrMalformedKeys() {
        WebAuthController controller = new WebAuthController(users, tokens, true);
        for (String key : new String[] {"short", "a".repeat(42), "a".repeat(129), "has spaces in it ".repeat(4)}) {
            assertThatThrownBy(() -> controller.webLogin(new WebAuthController.WebLoginRequest(key, null)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                    error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        }
        verify(users, never()).upsertWebUser(anyString(), anyString());
    }

    @Test
    void isHiddenWhenDisabled() {
        assertThatThrownBy(() -> new WebAuthController(users, tokens, false)
            .webLogin(new WebAuthController.WebLoginRequest(KEY, null)))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        verify(tokens, never()).issue(any());
    }

    @Test
    void refusesDisabledAccounts() {
        UserAccount disabled = new UserAccount(9L, null, "Community Resident", Role.RESIDENT, false);
        when(users.upsertWebUser(anyString(), anyString())).thenReturn(disabled);
        assertThatThrownBy(() -> new WebAuthController(users, tokens, true)
            .webLogin(new WebAuthController.WebLoginRequest(KEY, null)))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        verify(tokens, never()).issue(any());
    }
}
