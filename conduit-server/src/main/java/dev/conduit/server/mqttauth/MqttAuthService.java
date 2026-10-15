package dev.conduit.server.mqttauth;

import dev.conduit.server.device.DeviceIdentity;
import dev.conduit.server.device.DeviceIdentityLookup;
import dev.conduit.server.security.SecretHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;

@Service
public class MqttAuthService {

    public record Decision(boolean allow, boolean superuser) {
        public static final Decision DENY = new Decision(false, false);
    }

    static final String INTERNAL_CLIENT_PREFIX = "conduit-";
    private static final Logger log = LoggerFactory.getLogger(MqttAuthService.class);

    private final MqttAuthProperties props;
    private final DeviceIdentityLookup lookup;
    private final SecretHasher hasher;

    public MqttAuthService(MqttAuthProperties props, DeviceIdentityLookup lookup, SecretHasher hasher) {
        this.props = props;
        this.lookup = lookup;
        this.hasher = hasher;
    }

    public Decision authenticate(String clientId, String username, String password) {
        if (clientId == null || username == null || password == null) {
            return Decision.DENY;
        }
        if (username.equals(props.transportUsername())) {
            boolean ok = props.transportPassword() != null && !props.transportPassword().isBlank()
                    && constantEquals(password, props.transportPassword())
                    && clientId.startsWith(INTERNAL_CLIENT_PREFIX);
            return ok ? new Decision(true, true) : deny("transport credentials rejected", clientId);
        }
        if (!clientId.equals(username)) {
            return deny("clientId does not match username", clientId);
        }
        Optional<DeviceIdentity> identity = lookup.find(username);
        if (identity.isEmpty()) {
            return deny("unknown device", clientId);
        }
        DeviceIdentity d = identity.get();
        if (!d.enabled()) {
            return deny("device disabled", clientId);
        }
        if (!hasher.matches(password, d.secretSalt(), d.secretHash())) {
            return deny("wrong secret", clientId);
        }
        return new Decision(true, false);
    }

    static boolean constantEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static Decision deny(String reason, String clientId) {
        log.debug("MQTT auth denied for {}: {}", clientId, reason);
        return Decision.DENY;
    }
}
