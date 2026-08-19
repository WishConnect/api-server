package com.wishconnect.domain.scholarship.dto;

import java.time.LocalDateTime;

/** 관리자 신규 수집 검수 목록의 원본 한 줄. */
public record AdminIntakeRowResponse(
		Long rawId,
		Long scholarshipId,
		String title,
		String source,
		String sourceId,
		String sourceUrl,
		String parseStatus,
		String parseError,
		LocalDateTime crawledAt
) {
}
