package com.wishconnect.domain.application.dto.response;

import com.wishconnect.domain.application.entity.EssayStatus;
import java.time.LocalDateTime;
import java.util.List;

/**
 * ③ GET /api/v1/applications/{applicationId} 통합 상세 조회 응답.
 * 지원서 화면 진입 시 필요한 모든 데이터를 1회 호출로 반환한다.
 */
public record ApplicationDetailResponse(
		Long applicationId,
		String scholarshipTitle,
		EssayStatus status,
		LocalDateTime lastEditedAt,
		List<QuestionDetailResponse> questions
) {
}
