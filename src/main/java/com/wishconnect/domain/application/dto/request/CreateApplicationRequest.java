package com.wishconnect.domain.application.dto.request;

import jakarta.validation.constraints.NotNull;

/**
 * ② POST /api/v1/applications 요청 바디.
 */
public record CreateApplicationRequest(
		@NotNull Long scholarshipId
) {
}
