package dev.conduit.server.pipeline;

public record RejectedField(String field, Object value, RejectReason reason) {
}
