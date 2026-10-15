package dev.conduit.simulator;

import java.util.Locale;
import java.util.Map;

/** 上报格式，与平台解码器一一对应。 */
public enum PayloadFormat {

    STANDARD("standard-json") {
        @Override
        public byte[] encode(String id, long ts, Map<String, Object> params) {
            return PayloadFactory.property(id, ts, params);
        }
    },
    FLAT("flat-json") {
        @Override
        public byte[] encode(String id, long ts, Map<String, Object> params) {
            return PayloadFactory.flat(id, ts, params);
        }
    };

    private final String codec;

    PayloadFormat(String codec) {
        this.codec = codec;
    }

    public String codec() {
        return codec;
    }

    public abstract byte[] encode(String id, long ts, Map<String, Object> params);

    public static PayloadFormat parse(String value) {
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "standard" -> STANDARD;
            case "flat" -> FLAT;
            default -> throw new IllegalStateException("SIM_PAYLOAD_FORMAT must be standard or flat, got: " + value);
        };
    }
}
