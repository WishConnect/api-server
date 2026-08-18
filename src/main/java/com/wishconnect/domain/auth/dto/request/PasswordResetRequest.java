package com.wishconnect.domain.auth.dto.request;

import jakarta.validation.constraints.NotBlank;

public record PasswordResetRequest(
		@NotBlank String resetToken,
		@NotBlank String newPassword
) {
}
