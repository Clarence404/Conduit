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

    /**
     * 编码为 Stream 字段：productKey 为空时省略 pk；payload 转 Base64；
     * 附加属性以 {@code attr.} 为前缀逐项展开。
     */
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

    /**
     * 从 Stream 字段还原消息，{@link #toFields} 的逆操作。pk 可缺省（返回 null）。
     *
     * @throws IllegalArgumentException 必填字段缺失；type 非法、Base64 或数字格式错误时同样抛出
     *                                  （对应的 valueOf / decode / parseLong 异常均为其子类）
     */
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
