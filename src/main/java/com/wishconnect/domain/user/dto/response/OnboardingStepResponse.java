package com.wishconnect.domain.user.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

public record OnboardingStepResponse(
		@Schema(description = "완료한 온보딩 단계", example = "2")
		int step,
		@Schema(description = "단계 저장 완료 여부", example = "true")
		boolean completed
) {
}
