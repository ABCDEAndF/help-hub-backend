package org.isolatedareas.helphub.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final UserRepository users;
    private final JwtService tokens;
    private final WechatAuthService wechat;
    private final boolean devLoginEnabled;

    public AuthController(
        UserRepository users,
        JwtService tokens,
        WechatAuthService wechat,
        @Value("${app.auth.dev-login-enabled}") boolean devLoginEnabled
    ) {
        this.users = users;
        this.tokens = tokens;
        this.wechat = wechat;
        this.devLoginEnabled = devLoginEnabled;
    }

    @PostMapping("/dev-login")
    JwtService.TokenResponse devLogin(@Valid @RequestBody DevLoginRequest request) {
        if (!devLoginEnabled) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        UserAccount user = users.findByPhone(request.phone())
            .filter(UserAccount::enabled)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unknown development account"));
        return tokens.issue(user);
    }

    @PostMapping("/wechat")
    JwtService.TokenResponse wechatLogin(@Valid @RequestBody WechatLoginRequest request) {
        String openId = wechat.exchangeCode(request.code());
        UserAccount user = users.upsertWechatUser(openId,
            request.displayName() == null || request.displayName().isBlank() ? "Community Resident" : request.displayName());
        return tokens.issue(user);
    }

    public record DevLoginRequest(@NotBlank String phone) {
    }

    public record WechatLoginRequest(@NotBlank String code, String displayName) {
    }
}

