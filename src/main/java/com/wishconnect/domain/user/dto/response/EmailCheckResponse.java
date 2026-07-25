package com.wishconnect.domain.user.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

public record EmailCheckResponse(
		@Schema(description = "이메일 사용 가능 여부", example = "true")
		boolean available
) {
}
