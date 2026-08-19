package com.wishconnect.domain.scholarship.dto;

import java.time.LocalDateTime;

public record AdminRawFailureResponse(
		Long rawId,
		Long scholarshipId,
		String source,
		String sourceId,
		String sourceUrl,
		String status,
		String error,
		LocalDateTime crawledAt,
		LocalDateTime updatedAt
) {
}
