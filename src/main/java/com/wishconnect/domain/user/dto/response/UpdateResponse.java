package com.wishconnect.domain.user.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

public record UpdateResponse(
		@Schema(description = "변경 성공 여부", example = "true")
		boolean updated
) {
}
