package com.wishconnect.domain.application.dto.response;

import com.wishconnect.domain.application.entity.EssayStatus;

/**
 * ② POST /api/v1/applications 응답.
 */
public record CreateApplicationResponse(
		Long applicationId,
		EssayStatus status,
		int questionCount
) {
}
