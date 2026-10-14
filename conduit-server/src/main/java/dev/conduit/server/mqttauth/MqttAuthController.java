package dev.conduit.server.mqttauth;

import dev.conduit.server.mqttauth.MqttAuthService.Decision;
import dev.conduit.server.web.ApiException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** EMQX HTTP 鉴权回调。只返回 200 + allow/deny；内部 token 不对返回 401（EMQX 视为失败并拒绝连接）。 */
@RestController
public class MqttAuthController {

    public record MqttAuthRequest(String clientid, String username, String password) {
    }

    private final MqttAuthService service;
    private final MqttAuthProperties props;

    public MqttAuthController(MqttAuthService service, MqttAuthProperties props) {
        this.service = service;
        this.props = props;
    }

    @PostMapping("/internal/mqtt/auth")
    public Map<String, Object> authenticate(
            @RequestHeader(value = "X-Conduit-Internal-Token", required = false) String token,
            @RequestBody MqttAuthRequest request) {
        if (token == null || props.token() == null || props.token().isBlank()
                || !MqttAuthService.constantEquals(token, props.token())) {
            throw ApiException.unauthorized("invalid internal token");
        }
        Decision decision = service.authenticate(request.clientid(), request.username(), request.password());
        if (!decision.allow()) {
            return Map.of("result", "deny");
        }
        return Map.of("result", "allow", "is_superuser", decision.superuser());
    }
}
