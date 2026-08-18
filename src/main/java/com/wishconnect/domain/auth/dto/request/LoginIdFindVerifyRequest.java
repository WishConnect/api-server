package com.wishconnect.domain.auth.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record LoginIdFindVerifyRequest(
		@NotBlank @Email String email,
		@NotBlank String name,
		@NotBlank @Pattern(regexp = "\\d{6}") String code
) {
}
