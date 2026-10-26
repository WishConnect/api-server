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
		LocalDateTime updatedAt,
		/** 만료 처리 시각(만료 처리 안 됐으면 null). */
		LocalDateTime dismissedAt,
		/** 만료 처리 사유(RawDismissReason 이름). */
		String dismissedReason,
		String dismissedNote,
		/** 연결 장학금의 모집 상태. 만료 처리 모달이 "연결 장학금 마감" 대상 수를 세는 데 쓴다. */
		String linkedRecruitmentStatus,
		/** 연결 장학금이 내려졌는지. */
		boolean linkedScholarshipDeleted
) {
}
