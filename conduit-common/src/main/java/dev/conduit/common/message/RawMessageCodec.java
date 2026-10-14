package dev.conduit.common.message;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** RawMessage 与 Redis Stream 字段之间的转换。payload 用 Base64 存储以支持二进制协议。 */
public final class RawMessageCodec {

    static final String PRODUCT_KEY = "pk";
    static final String DEVICE_KEY = "dk";
    static final String TYPE = "type";
    static final String PAYLOAD = "payload";
    static final String RECEIVED_AT = "receivedAt";
    static final String ATTR_PREFIX = "attr.";

    private RawMessageCodec() {
    }

    public static Map<String, String> toFields(RawMessage message) {
        Map<String, String> fields = new LinkedHashMap<>();
        if (message.productKey() != null) {
            fields.put(PRODUCT_KEY, message.productKey());
        }
        fields.put(DEVICE_KEY, message.deviceKey());
        fields.put(TYPE, message.type().name());
        fields.put(PAYLOAD, Base64.getEncoder().encodeToString(message.payload()));
        fields.put(RECEIVED_AT, Long.toString(message.receivedAt()));
        message.attributes().forEach((k, v) -> fields.put(ATTR_PREFIX + k, v));
        return fields;
    }

    public static RawMessage fromFields(Map<String, String> fields) {
        String deviceKey = require(fields, DEVICE_KEY);
        MessageType type = MessageType.valueOf(require(fields, TYPE));
        byte[] payload = Base64.getDecoder().decode(require(fields, PAYLOAD));
        long receivedAt = Long.parseLong(require(fields, RECEIVED_AT));

        Map<String, String> attributes = new LinkedHashMap<>();
        fields.forEach((k, v) -> {
            if (k.startsWith(ATTR_PREFIX)) {
                attributes.put(k.substring(ATTR_PREFIX.length()), v);
            }
        });
        return new RawMessage(fields.get(PRODUCT_KEY), deviceKey, type, payload, receivedAt, attributes);
    }

    private static String require(Map<String, String> fields, String name) {
        String value = fields.get(name);
        if (value == null) {
            throw new IllegalArgumentException("missing field: " + name);
        }
        return value;
    }
}
