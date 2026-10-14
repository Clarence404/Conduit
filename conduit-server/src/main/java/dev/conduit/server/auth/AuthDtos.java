package dev.conduit.server.auth;

import jakarta.validation.constraints.NotBlank;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    public record UserView(String username, String role) {
    }

    public record TokenResponse(String accessToken, long expiresIn, UserView user) {
    }
}
