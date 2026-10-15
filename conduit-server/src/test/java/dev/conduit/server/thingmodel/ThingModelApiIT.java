package dev.conduit.server.thingmodel;

import com.jayway.jsonpath.JsonPath;
import dev.conduit.server.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ThingModelApiIT extends IntegrationTestBase {

    static final String UA = """
            {"identifier":"Ua","name":"A 相电压","dataType":"DOUBLE","unit":"V","minValue":0,"maxValue":500,"decimals":1}""";
    static final String ST = """
            {"identifier":"st","name":"状态","dataType":"ENUM","enumItems":[{"value":0,"label":"停止"},{"value":1,"label":"运行"}]}""";
    static final String MODE = """
            {"identifier":"mode","name":"模式","dataType":"TEXT","description":"运行模式"}""";

    String auth;

    @BeforeEach
    void login() throws Exception {
        auth = bearer(loginAsAdmin());
    }

    private String createProduct() throws Exception {
        String body = mvc.perform(post("/api/v1/products").header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"tm\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.productKey");
    }

    private ResultActions postProperty(String pk, String json) throws Exception {
        return mvc.perform(post("/api/v1/products/{pk}/properties", pk).header(HttpHeaders.AUTHORIZATION, auth)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions putProperty(String pk, String identifier, String json) throws Exception {
        return mvc.perform(put("/api/v1/products/{pk}/properties/{id}", pk, identifier)
                .header(HttpHeaders.AUTHORIZATION, auth).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions listProperties(String pk) throws Exception {
        return mvc.perform(get("/api/v1/products/{pk}/properties", pk).header(HttpHeaders.AUTHORIZATION, auth));
    }

    private ResultActions importModel(String pk, String mode, String json) throws Exception {
        return mvc.perform(put("/api/v1/products/{pk}/thing-model", pk).param("mode", mode)
                .header(HttpHeaders.AUTHORIZATION, auth).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private String export(String pk) throws Exception {
        return mvc.perform(get("/api/v1/products/{pk}/thing-model", pk).header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void createListUpdateDelete() throws Exception {
        String pk = createProduct();

        postProperty(pk, UA).andExpect(status().isCreated())
                .andExpect(jsonPath("$.identifier").value("Ua"))
                .andExpect(jsonPath("$.dataType").value("DOUBLE"))
                .andExpect(jsonPath("$.maxValue").value(500.0))
                .andExpect(jsonPath("$.decimals").value(1));
        postProperty(pk, ST).andExpect(status().isCreated())
                .andExpect(jsonPath("$.enumItems[1].label").value("运行"));

        listProperties(pk).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].identifier").value("Ua"))
                .andExpect(jsonPath("$[1].identifier").value("st"));

        putProperty(pk, "Ua", """
                {"name":"A 相电压（新）","dataType":"DOUBLE","unit":"kV","decimals":3}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("A 相电压（新）"))
                .andExpect(jsonPath("$.unit").value("kV"))
                .andExpect(jsonPath("$.maxValue").doesNotExist());

        mvc.perform(delete("/api/v1/products/{pk}/properties/Ua", pk).header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isNoContent());
        listProperties(pk).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void duplicateIdentifierIsConflict() throws Exception {
        String pk = createProduct();
        postProperty(pk, UA).andExpect(status().isCreated());

        postProperty(pk, UA).andExpect(status().isConflict());
    }

    @Test
    void changingDataTypeIsConflict() throws Exception {
        String pk = createProduct();
        postProperty(pk, UA).andExpect(status().isCreated());

        putProperty(pk, "Ua", "{\"name\":\"x\",\"dataType\":\"INT\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("数据类型不可修改"));
    }

    @Test
    void invalidPropertiesAreBadRequest() throws Exception {
        String pk = createProduct();
        for (String json : new String[]{
                "{\"identifier\":\"1a\",\"name\":\"x\",\"dataType\":\"DOUBLE\"}",
                "{\"identifier\":\"time\",\"name\":\"x\",\"dataType\":\"DOUBLE\"}",
                "{\"identifier\":\"a\",\"name\":\"x\",\"dataType\":\"FLOAT\"}",
                "{\"identifier\":\"a\",\"name\":\"x\",\"dataType\":\"DOUBLE\",\"minValue\":5,\"maxValue\":1}",
                "{\"identifier\":\"a\",\"name\":\"x\",\"dataType\":\"BOOL\",\"minValue\":0}",
                "{\"identifier\":\"a\",\"name\":\"x\",\"dataType\":\"ENUM\"}",
                "{\"identifier\":\"a\",\"name\":\"x\",\"dataType\":\"ENUM\",\"enumItems\":[{\"value\":1.5,\"label\":\"x\"}]}",
                "{\"identifier\":\"a\",\"name\":\"x\",\"dataType\":\"DOUBLE\",\"decimals\":7}"}) {
            postProperty(pk, json).andExpect(status().isBadRequest());
        }
        listProperties(pk).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void moreThan200PropertiesIsConflict() throws Exception {
        String pk = createProduct();
        jdbc.update("insert into product_property (tenant_id, product_id, identifier, name, data_type) "
                + "select p.tenant_id, p.id, 'p' || g, 'p' || g, 'DOUBLE' from product p, generate_series(1, 200) g "
                + "where p.product_key = ?", pk);

        postProperty(pk, UA).andExpect(status().isConflict());
    }

    @Test
    void unknownPropertyIsNotFound() throws Exception {
        String pk = createProduct();

        putProperty(pk, "nope", "{\"name\":\"x\",\"dataType\":\"DOUBLE\"}").andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/products/{pk}/properties/nope", pk).header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isNotFound());
    }

    @Test
    void otherTenantsProductIsNotFound() throws Exception {
        TestDevice foreign = createDevice(createTenant("tm-"), "ENABLED");

        listProperties(foreign.productKey()).andExpect(status().isNotFound());
        postProperty(foreign.productKey(), UA).andExpect(status().isNotFound());
        importModel(foreign.productKey(), "merge", "{\"properties\":[]}").andExpect(status().isNotFound());
    }

    @Test
    void exportThenImportRoundTrips() throws Exception {
        String a = createProduct();
        postProperty(a, UA).andExpect(status().isCreated());
        postProperty(a, ST).andExpect(status().isCreated());
        postProperty(a, MODE).andExpect(status().isCreated());
        String exported = export(a);
        assertThat(JsonPath.<Integer>read(exported, "$.properties.length()")).isEqualTo(3);
        assertThat(JsonPath.<java.util.Map<String, Object>>read(exported, "$.properties[0]"))
                .containsOnlyKeys("identifier", "name", "dataType", "unit", "minValue", "maxValue", "decimals",
                        "enumItems", "description");

        String b = createProduct();
        importModel(b, "replace", exported).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3));

        assertThat(export(b)).isEqualTo(exported);
    }

    @Test
    void failedImportChangesNothingAndListsAllErrors() throws Exception {
        String pk = createProduct();
        postProperty(pk, UA).andExpect(status().isCreated());

        importModel(pk, "merge", """
                {"properties":[
                  {"identifier":"Ib","name":"B 相电流","dataType":"DOUBLE"},
                  {"identifier":"Ua","name":"A 相电压","dataType":"INT"},
                  {"identifier":"1bad","name":"x","dataType":"DOUBLE"}]}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].identifier", hasItems("Ua", "1bad")));

        listProperties(pk).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].identifier").value("Ua"));
    }

    @Test
    void mergeKeepsOthersAndReplaceDeletesThem() throws Exception {
        String pk = createProduct();
        postProperty(pk, UA).andExpect(status().isCreated());
        postProperty(pk, MODE).andExpect(status().isCreated());

        importModel(pk, "merge", "{\"properties\":[" + ST + "]}").andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));
        importModel(pk, "replace", "{\"properties\":[" + ST + "]}").andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].identifier").value("st"));
    }

    @Test
    void importOver200IsRejectedAndBadModeIsBadRequest() throws Exception {
        String pk = createProduct();
        StringBuilder json = new StringBuilder("{\"properties\":[");
        for (int i = 0; i < 201; i++) {
            json.append(i == 0 ? "" : ",").append("{\"identifier\":\"p").append(i)
                    .append("\",\"name\":\"p\",\"dataType\":\"DOUBLE\"}");
        }
        json.append("]}");

        importModel(pk, "replace", json.toString()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()").value(1));
        importModel(pk, "upsert", "{\"properties\":[]}").andExpect(status().isBadRequest());
        importModel(pk, "merge", "{}").andExpect(status().isBadRequest());
    }
}
