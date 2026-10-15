package dev.conduit.server.pipeline.codec;

import dev.conduit.server.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CodecApiIT extends IntegrationTestBase {

    @Test
    void listsBuiltInCodecs() throws Exception {
        mvc.perform(get("/api/v1/codecs").header(HttpHeaders.AUTHORIZATION, bearer(loginAsAdmin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("flat-json"))
                .andExpect(jsonPath("$[0].displayName").value("扁平 JSON"))
                .andExpect(jsonPath("$[1].name").value("standard-json"))
                .andExpect(jsonPath("$[1].displayName").value("标准 JSON"));
    }

    @Test
    void requiresAuthentication() throws Exception {
        mvc.perform(get("/api/v1/codecs")).andExpect(status().isUnauthorized());
    }
}
