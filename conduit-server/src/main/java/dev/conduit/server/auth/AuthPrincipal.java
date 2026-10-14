package dev.conduit.server.auth;

public record AuthPrincipal(long userId, long tenantId, String username, String role) {
}
