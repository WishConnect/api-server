package com.wishconnect.domain.user.dto.request;

import jakarta.validation.constraints.NotBlank;

public record PasswordUpdateRequest(
		@NotBlank String currentPassword,
		@NotBlank String newPassword,
		@NotBlank String newPasswordConfirm
) {
}
