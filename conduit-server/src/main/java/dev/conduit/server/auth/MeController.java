package dev.conduit.server.auth;

import dev.conduit.server.auth.AuthDtos.ChangePasswordRequest;
import dev.conduit.server.auth.AuthDtos.TokenResponse;
import dev.conduit.server.auth.AuthDtos.UpdateMeRequest;
import dev.conduit.server.auth.AuthDtos.UserView;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 个人接口（/api/v1/me，所有登录用户）：修改显示名称、修改密码；逻辑在 {@link MeService}。 */
@RestController
@RequestMapping("/api/v1/me")
public class MeController {

    private final MeService service;
    private final SessionService sessions;

    public MeController(MeService service, SessionService sessions) {
        this.service = service;
        this.sessions = sessions;
    }

    /** 修改自己的显示名称，返回更新后的当前用户。 */
    @PutMapping
    public UserView update(@AuthenticationPrincipal AuthPrincipal principal,
                           @RequestBody UpdateMeRequest request) {
        return service.updateProfile(principal, request);
    }

    /** 修改自己的密码；成功后换发令牌（不再带需改密码标记）并写入新的 refresh Cookie。 */
    @PutMapping("/password")
    public ResponseEntity<TokenResponse> changePassword(@AuthenticationPrincipal AuthPrincipal principal,
                                                        @Valid @RequestBody ChangePasswordRequest request) {
        return sessions.issue(service.changePassword(principal, request));
    }
}
