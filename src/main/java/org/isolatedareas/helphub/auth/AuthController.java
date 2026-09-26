package org.isolatedareas.helphub.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.isolatedareas.helphub.domain.Role;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
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
    private final String expectedWechatAppId;
    private final String expectedCloudEnv;
    static final org.springframework.security.crypto.password.PasswordEncoder PASSWORDS =
        new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();
    private static final String DUMMY_HASH = PASSWORDS.encode("no-such-account-" + java.util.UUID.randomUUID());
    private final String operatorPhone;
    private final String operatorPassword;

    public AuthController(
        UserRepository users,
        JwtService tokens,
        WechatAuthService wechat,
        @Value("${app.auth.dev-login-enabled}") boolean devLoginEnabled,
        @Value("${app.wechat.app-id}") String expectedWechatAppId,
        @Value("${app.wechat.cloud-env}") String expectedCloudEnv,
        @Value("${app.auth.operator-phone}") String operatorPhone,
        @Value("${app.auth.operator-password}") String operatorPassword
    ) {
        this.users = users;
        this.tokens = tokens;
        this.wechat = wechat;
        this.devLoginEnabled = devLoginEnabled;
        this.expectedWechatAppId = expectedWechatAppId;
        this.expectedCloudEnv = expectedCloudEnv;
        this.operatorPhone = operatorPhone;
        this.operatorPassword = operatorPassword;
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
            displayName(request.displayName()));
        return tokens.issue(user);
    }

    @PostMapping("/operator-login")
    JwtService.TokenResponse operatorLogin(@Valid @RequestBody OperatorLoginRequest request) {
        String account = request.phone().trim();
        boolean configuredAdmin = !operatorPhone.isBlank() && MessageDigest.isEqual(
            operatorPhone.getBytes(StandardCharsets.UTF_8), account.getBytes(StandardCharsets.UTF_8));
        boolean authenticated;
        if (configuredAdmin) {
            authenticated = !operatorPassword.isBlank() && MessageDigest.isEqual(
                operatorPassword.getBytes(StandardCharsets.UTF_8), request.password().getBytes(StandardCharsets.UTF_8));
        } else {
            // Staff accounts created through the admin API. A dummy hash keeps the timing of
            // unknown accounts the same as wrong passwords.
            String hash = users.staffPasswordHash(account).orElse(DUMMY_HASH);
            authenticated = PASSWORDS.matches(request.password(), hash) && !DUMMY_HASH.equals(hash);
        }
        if (!authenticated) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid operator credentials");
        }
        UserAccount user = users.findByPhone(account)
            .filter(UserAccount::enabled)
            .filter(staff -> staff.role() == Role.OPERATOR || staff.role() == Role.ADMIN)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "Operator account is unavailable"));
        return tokens.issue(user);
    }

    @PostMapping("/cloudrun")
    JwtService.TokenResponse cloudRunLogin(
        @RequestHeader(name = "X-WX-OPENID", required = false) String openId,
        @RequestHeader(name = "X-WX-APPID", required = false) String appId,
        @RequestHeader(name = "X-WX-ENV", required = false) String cloudEnv,
        @RequestBody(required = false) CloudRunLoginRequest request
    ) {
        if (openId == null || openId.isBlank() || openId.length() > 128
            || !expectedWechatAppId.equals(appId) || !expectedCloudEnv.equals(cloudEnv)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid CloudRun identity");
        }
        UserAccount user = users.upsertWechatUser(openId,
            displayName(request == null ? null : request.displayName()));
        return tokens.issue(user);
    }

    private String displayName(String value) {
        if (value == null || value.isBlank()) {
            return "Community Resident";
        }
        String trimmed = value.trim();
        return trimmed.length() <= 80 ? trimmed : trimmed.substring(0, 80);
    }

    public record DevLoginRequest(@NotBlank String phone) {
    }

    public record WechatLoginRequest(@NotBlank String code, String displayName) {
    }

    public record OperatorLoginRequest(@NotBlank String phone, @NotBlank String password) {
    }

    public record CloudRunLoginRequest(String displayName) {
    }
}
