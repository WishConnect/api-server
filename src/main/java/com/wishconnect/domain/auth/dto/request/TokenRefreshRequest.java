package com.wishconnect.domain.auth.dto.request;

import jakarta.validation.constraints.NotBlank;

public record TokenRefreshRequest(
		@NotBlank String refreshToken
) {
}
