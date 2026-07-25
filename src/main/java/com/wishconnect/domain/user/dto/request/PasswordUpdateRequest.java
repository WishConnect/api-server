package com.wishconnect.domain.user.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record PasswordUpdateRequest(
		@Schema(description = "현재 비밀번호", example = "Old1234!")
		@NotBlank String currentPassword,
		@Schema(description = "새 비밀번호", example = "New1234!")
		@NotBlank String newPassword,
		@Schema(description = "새 비밀번호 확인", example = "New1234!")
		@NotBlank String newPasswordConfirm
) {
}
