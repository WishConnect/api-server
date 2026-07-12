package com.wishconnect.domain.auth.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record EmailVerifyRequest(
		@NotBlank @Email String email,
		@NotBlank String code
) {
}
