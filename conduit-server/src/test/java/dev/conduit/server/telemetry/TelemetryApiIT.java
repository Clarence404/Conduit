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
                .andExpect(jsonPath("$.values[0].identifier").value("Ia"))
                .andExpect(jsonPath("$.values[0].name").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.values[0].value").value(5.0))
                .andExpect(jsonPath("$.values[0].ts").value(1000))
                .andExpect(jsonPath("$.values[2].identifier").value("mode"))
                .andExpect(jsonPath("$.values[2].value").value("auto"));
    }

    @Test
    void enrichesValuesWithThingModelMetadata() throws Exception {
        TestDevice device = createDevice(defaultTenantId(), "ENABLED");
        insertProperty(device.productKey(), "Ua", "A 相电压", "DOUBLE", "V", null, null, 1, null);
        insertProperty(device.productKey(), "st", "状态", "ENUM", null, null, null, null,
                "[{\"value\":0,\"label\":\"停止\"},{\"value\":1,\"label\":\"运行\"}]");
        latest.update(device.deviceKey(), 1000, Map.of("Ua", 220.1, "st", 1L, "Zz", 3.0));

        mvc.perform(get("/api/v1/devices/{dk}/latest", device.deviceKey())
                        .header(HttpHeaders.AUTHORIZATION, bearer(loginAsAdmin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.values[0].identifier").value("Ua"))
                .andExpect(jsonPath("$.values[0].name").value("A 相电压"))
                .andExpect(jsonPath("$.values[0].unit").value("V"))
                .andExpect(jsonPath("$.values[0].decimals").value(1))
                .andExpect(jsonPath("$.values[0].enumLabel").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.values[1].identifier").value("Zz"))
                .andExpect(jsonPath("$.values[1].name").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.values[2].identifier").value("st"))
                .andExpect(jsonPath("$.values[2].enumLabel").value("运行"));
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
