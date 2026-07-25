package com.wishconnect.domain.user.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record EmailCheckRequest(
		@Schema(description = "중복 확인할 이메일", example = "new-email@example.com")
		@NotBlank @Email String email
) {
}
