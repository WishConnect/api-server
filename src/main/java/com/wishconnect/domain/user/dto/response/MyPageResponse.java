package com.wishconnect.domain.user.dto.response;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record MyPageResponse(
		UUID userId,
		String name,
		String email,
		String birthYear,
		String region,
		int profileCompletionRate,
		long scrappedCount,
		long applicationCount,
		long completedCount,
		RecommendationCriteria recommendationCriteria
) {

	public record RecommendationCriteria(
			String grade,
			BigDecimal gpa,
			String incomeLevel,
			List<String> interests
	) {
	}
}
