package com.wishconnect.domain.notification.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "수정 처리 응답")
public record UpdateResponse(
		boolean updated
) {
}
