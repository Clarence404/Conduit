package dev.conduit.server.telemetry;

import dev.conduit.server.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TelemetryApiIT extends IntegrationTestBase {

    @Autowired
    LatestValueStore latest;

    @Test
    void returnsLatestValuesSortedByName() throws Exception {
        TestDevice device = createDevice(defaultTenantId(), "ENABLED");
        latest.update(device.deviceKey(), 1000, Map.of("Ua", 220.0, "Ia", 5.0, "mode", "auto"));

        mvc.perform(get("/api/v1/devices/{dk}/latest", device.deviceKey())
                        .header(HttpHeaders.AUTHORIZATION, bearer(loginAsAdmin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deviceKey").value(device.deviceKey()))
                .andExpect(jsonPath("$.values.length()").value(3))
                .andExpect(jsonPath("$.values[0].name").value("Ia"))
                .andExpect(jsonPath("$.values[0].value").value(5.0))
                .andExpect(jsonPath("$.values[0].ts").value(1000))
                .andExpect(jsonPath("$.values[2].name").value("mode"))
                .andExpect(jsonPath("$.values[2].value").value("auto"));
    }

    @Test
    void deviceWithoutDataReturnsEmptyList() throws Exception {
        TestDevice device = createDevice(defaultTenantId(), "ENABLED");

        mvc.perform(get("/api/v1/devices/{dk}/latest", device.deviceKey())
                        .header(HttpHeaders.AUTHORIZATION, bearer(loginAsAdmin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.values.length()").value(0));
    }

    @Test
    void otherTenantsDeviceIsNotFound() throws Exception {
        TestDevice foreign = createDevice(createTenant("other-"), "ENABLED");
        latest.update(foreign.deviceKey(), 1000, Map.of("Ua", 1.0));

        mvc.perform(get("/api/v1/devices/{dk}/latest", foreign.deviceKey())
                        .header(HttpHeaders.AUTHORIZATION, bearer(loginAsAdmin())))
                .andExpect(status().isNotFound());
    }
}
