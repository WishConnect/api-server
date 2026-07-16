package com.wishconnect.domain.application.dto.response;

import com.wishconnect.domain.application.entity.EssayStatus;
import java.time.LocalDateTime;

/**
 * 지원서 목록의 각 항목. Notion 명세서 ① 응답의 content 요소에 대응.
 */
public record ApplicationListItemResponse(
		Long applicationId,
		Long scholarshipId,
		String scholarshipTitle,
		EssayStatus status,
		ProgressResponse progress,
		LocalDateTime lastEditedAt
) {
}
