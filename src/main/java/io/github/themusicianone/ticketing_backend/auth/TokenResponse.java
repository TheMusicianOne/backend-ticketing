package io.github.themusicianone.ticketing_backend.auth;

public record TokenResponse(String accessToken, String tokenType, long expiresIn) {
}