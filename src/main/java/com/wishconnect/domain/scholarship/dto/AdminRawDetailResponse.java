package com.wishconnect.domain.scholarship.dto;

import java.time.LocalDateTime;
import java.util.Map;

/** 파싱 전 원본도 관리자 화면에서 확인할 수 있는 통합 상세. */
public record AdminRawDetailResponse(
		Long rawId,
		Long scholarshipId,
		String source,
		String sourceId,
		String sourceUrl,
		Map<String, Object> rawJson,
		String rawHtml,
		String parseStatus,
		String parseError,
		LocalDateTime crawledAt,
		AdminScholarshipDetailResponse scholarship
) {
}
