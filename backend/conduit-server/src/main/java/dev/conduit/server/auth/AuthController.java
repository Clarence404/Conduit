package dev.conduit.server.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import dev.conduit.server.auth.AuthDtos.LoginRequest;
import dev.conduit.server.auth.AuthDtos.TokenResponse;
import dev.conduit.server.auth.AuthDtos.UserView;
import dev.conduit.server.web.ApiException;
import io.jsonwebtoken.JwtException;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    static final String REFRESH_COOKIE = "conduit_refresh";
    private static final String ACTIVE = "ACTIVE";

    private final AppUserMapper users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final JwtProperties props;

    public AuthController(AppUserMapper users, PasswordEncoder encoder, JwtService jwt, JwtProperties props) {
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
        this.props = props;
    }

    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        AppUser user = users.selectOne(new LambdaQueryWrapper<AppUser>().eq(AppUser::getUsername, request.username()));
        if (user == null || !ACTIVE.equals(user.getStatus())
                || !encoder.matches(request.password(), user.getPasswordHash())) {
            throw ApiException.unauthorized("用户名或密码错误");
        }
        return tokens(principalOf(user));
    }

    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw ApiException.unauthorized("缺少刷新凭证");
        }
        AuthPrincipal principal;
        try {
            principal = jwt.parse(refreshToken, JwtService.TokenType.REFRESH);
        } catch (JwtException | IllegalArgumentException e) {
            throw ApiException.unauthorized("刷新凭证无效");
        }
        AppUser user = users.selectById(principal.userId());
        if (user == null || !ACTIVE.equals(user.getStatus())) {
            throw ApiException.unauthorized("用户不可用");
        }
        return tokens(principalOf(user));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, refreshCookie("", Duration.ZERO).toString())
                .build();
    }

    @GetMapping("/me")
    public UserView me(@AuthenticationPrincipal AuthPrincipal principal) {
        return new UserView(principal.username(), principal.role());
    }

    private ResponseEntity<TokenResponse> tokens(AuthPrincipal principal) {
        String access = jwt.issue(principal, JwtService.TokenType.ACCESS);
        String refresh = jwt.issue(principal, JwtService.TokenType.REFRESH);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie(refresh, props.refreshTtl()).toString())
                .body(new TokenResponse(access, props.accessTtl().toSeconds(),
                        new UserView(principal.username(), principal.role())));
    }

    private ResponseCookie refreshCookie(String value, Duration maxAge) {
        return ResponseCookie.from(REFRESH_COOKIE, value)
                .httpOnly(true)
                .secure(props.cookieSecure())
                .sameSite("Strict")
                .path("/api/v1/auth")
                .maxAge(maxAge)
                .build();
    }

    private static AuthPrincipal principalOf(AppUser user) {
        return new AuthPrincipal(user.getId(), user.getTenantId(), user.getUsername(), user.getRole());
    }
}
