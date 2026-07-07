package com.wishconnect.domain.auth.dto.response;

import java.util.UUID;

public record SignupResponse(
		UUID userId,
		String accessToken,
		String refreshToken
) {
}
