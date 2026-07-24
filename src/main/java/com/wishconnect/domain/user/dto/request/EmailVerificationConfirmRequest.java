package com.wishconnect.domain.user.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record EmailVerificationConfirmRequest(
		@NotBlank @Email String email,
		@NotBlank String code
) {
}
