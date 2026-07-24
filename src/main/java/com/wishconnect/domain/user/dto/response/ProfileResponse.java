package com.wishconnect.domain.user.dto.response;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record ProfileResponse(
		UUID userId,
		String name,
		String email,
		String birthYear,
		String phone,
		String gender,
		String nationality,
		String region,
		int profileCompletionRate,
		boolean onboardingCompleted,
		Academic academic,
		Household household,
		List<String> interests
) {

	public record Academic(
			String university,
			String majorCategory,
			String majorName,
			String enrollmentStatus,
			String grade,
			BigDecimal semesterGpa,
			BigDecimal cumulativeGpa,
			String dualMajor
	) {
	}

	public record Household(
			String incomeLevel,
			Long familySize,
			List<String> familyTypes,
			List<String> personalStatuses
	) {
	}
}
