package dev.conduit.server.telemetry;

import java.util.List;

public record WriteResult(List<Rejected> rejected) {

    public record Rejected(int index, String message) {
    }

    public static WriteResult ok() {
        return new WriteResult(List.of());
    }
}
