package com.wishconnect.domain.user.dto.request;

import jakarta.validation.constraints.NotBlank;

public record ProfileBasicRequest(
		@NotBlank String name,
		@NotBlank String birthYear,
		@NotBlank String phone,
		@NotBlank String gender,
		@NotBlank String nationality,
		@NotBlank String region
) {
}
