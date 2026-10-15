package dev.conduit.server.pipeline.codec;

import java.util.Map;

public record DecodedPayload(long ts, Map<String, Object> values) {
}
