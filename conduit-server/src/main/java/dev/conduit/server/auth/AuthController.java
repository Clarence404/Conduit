package dev.conduit.server.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import dev.conduit.server.auth.AuthDtos.LoginRequest;
import dev.conduit.server.auth.AuthDtos.TokenResponse;
import dev.conduit.server.auth.AuthDtos.UserView;
import dev.conduit.server.tenant.Tenant;
import dev.conduit.server.tenant.TenantMapper;
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

/**
 * 登录认证 REST 接口（UI 使用）：登录签发短期 access token（响应体）与长期 refresh token（HttpOnly Cookie），
 * 并支持刷新、登出与查询当前用户。这些接口在 {@link SecurityConfig} 中免认证（me 除外）。
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    static final String REFRESH_COOKIE = "conduit_refresh";
    private static final String ACTIVE = "ACTIVE";

    private final AppUserMapper users;
    private final TenantMapper tenants;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final JwtProperties props;

    public AuthController(AppUserMapper users, TenantMapper tenants, PasswordEncoder encoder, JwtService jwt,
                          JwtProperties props) {
        this.users = users;
        this.tenants = tenants;
        this.encoder = encoder;
        this.jwt = jwt;
        this.props = props;
    }

    /**
     * 用户名密码登录。用户不存在、已停用或密码错误一律返回同一条 401，避免泄露账号是否存在。
     * 成功时响应体带 access token，refresh token 通过 Cookie 下发。
     */
    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        AppUser user = users.selectOne(new LambdaQueryWrapper<AppUser>().eq(AppUser::getUsername, request.username()));
        if (user == null || !ACTIVE.equals(user.getStatus())
                || !encoder.matches(request.password(), user.getPasswordHash())) {
            throw ApiException.unauthorized("用户名或密码错误");
        }
        return tokens(principalOf(user));
    }

    /**
     * 用 refresh Cookie 换发新的 access / refresh token（滚动续期）。Cookie 缺失、令牌无效或类型不符，
     * 以及用户已被删除或停用，均返回 401；角色等信息以数据库最新值为准而非旧令牌。
     */
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

    /** 登出：仅通过 Max-Age=0 清除 refresh Cookie；令牌无服务端状态，已签发的 access token 到期前仍有效。 */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, refreshCookie("", Duration.ZERO).toString())
                .build();
    }

    /** 当前登录用户（含所属租户名称）。 */
    @GetMapping("/me")
    public UserView me(@AuthenticationPrincipal AuthPrincipal principal) {
        return userView(principal);
    }

    /** 为用户签发 access + refresh 令牌：refresh 写入 Cookie，access 与有效期（秒）放在响应体。 */
    private ResponseEntity<TokenResponse> tokens(AuthPrincipal principal) {
        String access = jwt.issue(principal, JwtService.TokenType.ACCESS);
        String refresh = jwt.issue(principal, JwtService.TokenType.REFRESH);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie(refresh, props.refreshTtl()).toString())
                .body(new TokenResponse(access, props.accessTtl().toSeconds(), userView(principal)));
    }

    /** refresh Cookie：HttpOnly、SameSite=Strict、仅发往 /api/v1/auth；Secure 由配置决定（生产应开启）。 */
    private ResponseCookie refreshCookie(String value, Duration maxAge) {
        return ResponseCookie.from(REFRESH_COOKIE, value)
                .httpOnly(true)
                .secure(props.cookieSecure())
                .sameSite("Strict")
                .path("/api/v1/auth")
                .maxAge(maxAge)
                .build();
    }

    /**
     * 组装给 UI 的用户视图：租户名称按 tenantId 查 tenant 表（该表不做租户行过滤）；租户不存在时名称为 null。
     */
    private UserView userView(AuthPrincipal principal) {
        Tenant tenant = tenants.selectById(principal.tenantId());
        return new UserView(principal.username(), principal.role(), tenant == null ? null : tenant.getName());
    }

    private static AuthPrincipal principalOf(AppUser user) {
        return new AuthPrincipal(user.getId(), user.getTenantId(), user.getUsername(), user.getRole());
    }
}
