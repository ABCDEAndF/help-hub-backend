package org.isolatedareas.helphub.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Sign-in for the web version of the resident app. A browser has no WeChat identity, so it
 * generates a random device key once and keeps it; the key works like a bearer credential for
 * that browser's resident account. Only its SHA-256 is stored, in its own column, so a web
 * resident can never be mistaken for (or impersonate) a WeChat one.
 */
@RestController
@RequestMapping("/api/auth")
public class WebAuthController {
    // At least 256 bits of base64url randomness; anything shorter is guessable.
    private static final Pattern DEVICE_KEY = Pattern.compile("[A-Za-z0-9_-]{43,128}");

    private final UserRepository users;
    private final JwtService tokens;
    private final boolean enabled;

    public WebAuthController(
        UserRepository users,
        JwtService tokens,
        @Value("${app.auth.web-login-enabled}") boolean enabled
    ) {
        this.users = users;
        this.tokens = tokens;
        this.enabled = enabled;
    }

    @PostMapping("/web")
    JwtService.TokenResponse webLogin(@Valid @RequestBody WebLoginRequest request) {
        if (!enabled) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        if (!DEVICE_KEY.matcher(request.deviceKey()).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid device key");
        }
        UserAccount user = users.upsertWebUser(sha256(request.deviceKey()), displayName(request.displayName()));
        if (!user.enabled()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Account is disabled");
        }
        return tokens.issue(user);
    }

    static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private static String displayName(String value) {
        if (value == null || value.isBlank()) {
            return "Community Resident";
        }
        String trimmed = value.trim();
        return trimmed.length() <= 80 ? trimmed : trimmed.substring(0, 80);
    }

    public record WebLoginRequest(@NotBlank String deviceKey, String displayName) {
    }
}
