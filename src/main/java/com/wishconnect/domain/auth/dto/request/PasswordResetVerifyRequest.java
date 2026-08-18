package com.wishconnect.domain.auth.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record PasswordResetVerifyRequest(
		@NotBlank String loginId,
		@NotBlank @Email String email,
		@NotBlank @Pattern(regexp = "\\d{6}") String code
) {
}
