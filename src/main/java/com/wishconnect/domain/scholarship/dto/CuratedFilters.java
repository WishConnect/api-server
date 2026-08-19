package com.wishconnect.domain.scholarship.dto;

import com.wishconnect.domain.scholarship.entity.ScholarshipType;
import io.swagger.v3.oas.annotations.media.Schema;

/** 마지막 목록(ineligible, other)에 공통 적용하는 구조화 필터. */
@Schema(description = "ineligibleScholarships·otherScholarships에 공통 적용하는 필터")
public record CuratedFilters(
		ScholarshipType scholarshipType,
		DeadlineFilter deadline,
		Integer deadlineWithinDays,
		Long minAmount,
		Long maxAmount,
		boolean scrappedOnly
) {
	public CuratedFilters {
		deadline = deadline == null ? DeadlineFilter.ALL : deadline;
	}

	public static CuratedFilters none() {
		return new CuratedFilters(null, DeadlineFilter.ALL, null, null, null, false);
	}
}
