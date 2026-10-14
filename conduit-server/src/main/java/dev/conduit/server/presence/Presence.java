package dev.conduit.server.presence;

public record Presence(boolean online, long since, String clientIp) {
}
