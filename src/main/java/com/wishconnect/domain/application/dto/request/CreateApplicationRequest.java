package com.wishconnect.domain.application.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * ② POST /api/v1/applications 요청 바디.
 */
@Schema(description = "지원서 작성 시작 요청")
public record CreateApplicationRequest(
		@Schema(description = "대상 장학금 ID", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull Long scholarshipId
) {
}
