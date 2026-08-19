package com.wishconnect.domain.scholarship.dto;

import java.time.LocalDateTime;

public record AlwaysOpenScholarshipResponse(
		Long id,
		String title,
		String provider,
		LocalDateTime createdAt,
		long conditionCount,
		String sourceUrl
) {
}
