package com.wishconnect.domain.auth.dto.response;

public record PasswordResetVerifyResponse(String resetToken, long expiresIn) {
}
