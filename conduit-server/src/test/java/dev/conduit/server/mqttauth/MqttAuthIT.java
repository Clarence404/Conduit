package dev.conduit.server.mqttauth;

import dev.conduit.server.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MqttAuthIT extends IntegrationTestBase {

    private ResultActions auth(String token, String clientId, String username, String password) throws Exception {
        var request = post("/internal/mqtt/auth").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"clientid":"%s","username":"%s","password":"%s"}""".formatted(clientId, username, password));
        if (token != null) {
            request.header("X-Conduit-Internal-Token", token);
        }
        return mvc.perform(request);
    }

    @Test
    void allowsDeviceWithCorrectSecret() throws Exception {
        TestDevice d = createDevice(defaultTenantId(), "ENABLED");

        auth("test-internal-token", d.deviceKey(), d.deviceKey(), d.secret())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("allow"))
                .andExpect(jsonPath("$.is_superuser").value(false));
    }

    @Test
    void deniesWrongSecret() throws Exception {
        TestDevice d = createDevice(defaultTenantId(), "ENABLED");

        auth("test-internal-token", d.deviceKey(), d.deviceKey(), "wrong")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("deny"));
    }

    @Test
    void deniesDisabledDevice() throws Exception {
        TestDevice d = createDevice(defaultTenantId(), "DISABLED");

        auth("test-internal-token", d.deviceKey(), d.deviceKey(), d.secret())
                .andExpect(jsonPath("$.result").value("deny"));
    }

    @Test
    void deniesClientIdMismatch() throws Exception {
        TestDevice d = createDevice(defaultTenantId(), "ENABLED");

        auth("test-internal-token", "someone-else", d.deviceKey(), d.secret())
                .andExpect(jsonPath("$.result").value("deny"));
    }

    @Test
    void deniesUnknownDevice() throws Exception {
        auth("test-internal-token", "ghost", "ghost", "x")
                .andExpect(jsonPath("$.result").value("deny"));
    }

    @Test
    void allowsConnectorAsSuperuser() throws Exception {
        auth("test-internal-token", "conduit-connector-1", "conduit-connector", "connector-pass")
                .andExpect(jsonPath("$.result").value("allow"))
                .andExpect(jsonPath("$.is_superuser").value(true));
    }

    @Test
    void deniesConnectorWithWrongPasswordOrClientId() throws Exception {
        auth("test-internal-token", "conduit-connector-1", "conduit-connector", "nope")
                .andExpect(jsonPath("$.result").value("deny"));
        auth("test-internal-token", "evil", "conduit-connector", "connector-pass")
                .andExpect(jsonPath("$.result").value("deny"));
    }

    @Test
    void rejectsMissingOrWrongInternalToken() throws Exception {
        auth(null, "c", "u", "p").andExpect(status().isUnauthorized());
        auth("wrong", "c", "u", "p").andExpect(status().isUnauthorized());
    }
}
