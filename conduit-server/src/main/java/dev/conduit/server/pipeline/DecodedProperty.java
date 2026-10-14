package dev.conduit.server.pipeline;

import java.util.Map;

public record DecodedProperty(long ts, Map<String, Object> params) {
}
