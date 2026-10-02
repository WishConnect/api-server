package com.wishconnect.domain.scholarship.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "관리자 수기 통합 등록 결과")
public record ScholarshipManualFullResponse(
		Long scholarshipId,
		Long rawScholarshipId,
		int conditionCount,
		int conditionRefCount,
		int documentCount,
		boolean imageSaved,
		@Schema(description = "이미지를 저장하지 못한 이유. 성공했거나 이미지를 보내지 않았으면 null")
		String imageError
) {

	public ScholarshipManualFullResponse(Long scholarshipId, Long rawScholarshipId, int conditionCount,
			int conditionRefCount, int documentCount, boolean imageSaved) {
		this(scholarshipId, rawScholarshipId, conditionCount, conditionRefCount, documentCount, imageSaved, null);
	}
}
