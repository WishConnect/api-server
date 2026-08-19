package com.wishconnect.domain.scholarship.dto;

import java.time.LocalDateTime;
import java.util.List;

public record AdminScholarshipAnomalyResponse(
		Long scholarshipId,
		String title,
		String provider,
		String recruitmentStatus,
		LocalDateTime applicationStartAt,
		LocalDateTime applicationEndAt,
		String source,
		List<String> anomalyTypes
) {
}
