package com.wishconnect.domain.user.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record EmailVerificationSendRequest(
		@Schema(description = "인증코드를 받을 변경 이메일", example = "new-email@example.com")
		@NotBlank @Email String email
) {
}
