package com.wishconnect.domain.scholarship.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "관리자 수기 통합 등록 결과")
public record ScholarshipManualFullResponse(
		Long scholarshipId,
		Long rawScholarshipId,
		int conditionCount,
		int conditionRefCount,
		int documentCount,
		boolean imageSaved
) {
}
