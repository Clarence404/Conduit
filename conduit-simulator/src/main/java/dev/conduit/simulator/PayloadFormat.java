package dev.conduit.simulator;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

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

    /** 按本格式编码一次上报；id 为消息标识，ts 为设备时间（毫秒），params 为测点读数。 */
    public abstract byte[] encode(String id, long ts, Map<String, Object> params);

    /** 解析配置值 standard / flat（忽略大小写与首尾空白）；其他值抛 IllegalStateException。 */
    public static PayloadFormat parse(String value) {
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "standard" -> STANDARD;
            case "flat" -> FLAT;
            default -> throw new IllegalStateException("SIM_PAYLOAD_FORMAT must be standard or flat, got: " + value);
        };
    }

    /**
     * 按平台解码器名称找对应的上报格式。
     *
     * @return standard-json → STANDARD，flat-json → FLAT；其他解码器（模拟器不会生成的格式）或 null 为 empty
     */
    public static Optional<PayloadFormat> fromCodec(String codec) {
        for (PayloadFormat format : values()) {
            if (format.codec.equals(codec)) {
                return Optional.of(format);
            }
        }
        return Optional.empty();
    }
}
