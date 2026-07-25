package com.wishconnect.domain.user.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record EmailVerificationConfirmRequest(
		@Schema(description = "인증코드를 받은 변경 이메일", example = "new-email@example.com")
		@NotBlank @Email String email,
		@Schema(description = "6자리 인증코드", example = "123456")
		@NotBlank String code
) {
}
