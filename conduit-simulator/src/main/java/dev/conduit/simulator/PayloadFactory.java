package dev.conduit.simulator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;

public final class PayloadFactory {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PayloadFactory() {
    }

    public static byte[] property(String id, long ts, Map<String, Object> params) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("ts", ts);
        body.put("params", params);
        return write(body);
    }

    /** 扁平格式：{"id":..., "ts":..., 测点: 值, ...}。 */
    public static byte[] flat(String id, long ts, Map<String, Object> params) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("ts", ts);
        body.putAll(params);
        return write(body);
    }

    private static byte[] write(Map<String, Object> body) {
        try {
            return MAPPER.writeValueAsBytes(body);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }
}
