package com.wishconnect.domain.scholarship.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "관리자가 원문을 확인해 수기 마감할 ALWAYS_OPEN 장학금")
public record AlwaysOpenScholarshipResponse(
		@Schema(description = "장학금 ID", example = "1024") Long id,
		@Schema(description = "장학금명") String title,
		@Schema(description = "운영 기관") String provider,
		@Schema(description = "데이터 생성 일시") LocalDateTime createdAt,
		@Schema(description = "저장된 자격·우대 조건 수", example = "4") long conditionCount,
		@Schema(description = "관리자 확인용 원문 URL. detailUrl을 우선하고 없으면 homepageUrl") String sourceUrl
) {
}
