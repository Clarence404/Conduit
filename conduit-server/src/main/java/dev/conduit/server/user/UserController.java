package dev.conduit.server.user;

import dev.conduit.server.auth.AppUser;
import dev.conduit.server.auth.AuthPrincipal;
import dev.conduit.server.user.UserDtos.CreateUserRequest;
import dev.conduit.server.user.UserDtos.PasswordRequest;
import dev.conduit.server.user.UserDtos.UpdateUserRequest;
import dev.conduit.server.user.UserDtos.UserItem;
import dev.conduit.server.web.PageView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 租户用户管理 REST 接口（/api/v1/users，租户管理员与代入的平台管理员，权限在 SecurityConfig）；逻辑在 {@link UserService}。
 */
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService service;

    public UserController(UserService service) {
        this.service = service;
    }

    /** 分页列出当前租户的用户（默认每页 10 条）；可按关键字、角色、状态筛选。 */
    @GetMapping
    public PageView<UserItem> list(@RequestParam(required = false) String keyword,
                                   @RequestParam(required = false) String role,
                                   @RequestParam(required = false) String status,
                                   @RequestParam(defaultValue = "1") @Min(1) int page,
                                   @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size) {
        return service.list(keyword, role, status, page, size);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserItem create(@Valid @RequestBody CreateUserRequest request) {
        return service.create(request);
    }

    @PutMapping("/{username}")
    public UserItem update(@AuthenticationPrincipal AuthPrincipal actor, @PathVariable String username,
                           @Valid @RequestBody UpdateUserRequest request) {
        return service.update(actor, username, request);
    }

    @PostMapping("/{username}/disable")
    public UserItem disable(@AuthenticationPrincipal AuthPrincipal actor, @PathVariable String username) {
        return service.setStatus(actor, username, AppUser.DISABLED);
    }

    @PostMapping("/{username}/enable")
    public UserItem enable(@AuthenticationPrincipal AuthPrincipal actor, @PathVariable String username) {
        return service.setStatus(actor, username, AppUser.ACTIVE);
    }

    @PostMapping("/{username}/reset-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetPassword(@AuthenticationPrincipal AuthPrincipal actor, @PathVariable String username,
                              @Valid @RequestBody PasswordRequest request) {
        service.resetPassword(actor, username, request);
    }

    @DeleteMapping("/{username}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthPrincipal actor, @PathVariable String username) {
        service.delete(actor, username);
    }
}
