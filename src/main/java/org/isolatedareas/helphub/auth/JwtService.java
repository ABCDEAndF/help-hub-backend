package org.isolatedareas.helphub.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

@Service
public class JwtService {
    private final JwtEncoder encoder;
    private final Duration tokenTtl;

    public JwtService(JwtEncoder encoder, @Value("${app.auth.token-ttl}") Duration tokenTtl) {
        this.encoder = encoder;
        this.tokenTtl = tokenTtl;
    }

    public TokenResponse issue(UserAccount user) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(tokenTtl);
        JwtClaimsSet claims = JwtClaimsSet.builder()
            .issuer("isolated-areas-help-hub")
            .issuedAt(now)
            .expiresAt(expiresAt)
            .subject(Long.toString(user.id()))
            .claim("name", user.displayName())
            .claim("roles", List.of(user.role().name()))
            .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new TokenResponse(token, expiresAt, user.id(), user.displayName(), user.role().name());
    }

    public record TokenResponse(String accessToken, Instant expiresAt, long userId, String displayName, String role) {
    }
}

