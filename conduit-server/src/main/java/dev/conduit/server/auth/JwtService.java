package dev.conduit.server.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

@Component
public class JwtService {

    public enum TokenType { ACCESS, REFRESH }

    private final JwtProperties props;
    private final Clock clock;
    private final SecretKey key;

    public JwtService(JwtProperties props, Clock clock) {
        if (props.jwtSecret() == null || props.jwtSecret().getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("conduit.auth.jwt-secret must be at least 32 bytes");
        }
        this.props = props;
        this.clock = clock;
        this.key = Keys.hmacShaKeyFor(props.jwtSecret().getBytes(StandardCharsets.UTF_8));
    }

    public String issue(AuthPrincipal principal, TokenType type) {
        Instant now = clock.instant();
        Duration ttl = type == TokenType.ACCESS ? props.accessTtl() : props.refreshTtl();
        return Jwts.builder()
                .subject(Long.toString(principal.userId()))
                .claim("tid", principal.tenantId())
                .claim("usr", principal.username())
                .claim("role", principal.role())
                .claim("typ", type.name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key)
                .compact();
    }

    public AuthPrincipal parse(String token, TokenType expected) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .clock(() -> Date.from(clock.instant()))
                .build()
                .parseSignedClaims(token)
                .getPayload();
        if (!expected.name().equals(claims.get("typ", String.class))) {
            throw new JwtException("Unexpected token type");
        }
        return new AuthPrincipal(
                Long.parseLong(claims.getSubject()),
                ((Number) claims.get("tid")).longValue(),
                claims.get("usr", String.class),
                claims.get("role", String.class));
    }
}
