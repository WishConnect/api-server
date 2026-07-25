package com.wishconnect.domain.user.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record MyPageResponse(
		@Schema(description = "사용자 ID", example = "550e8400-e29b-41d4-a716-446655440000")
		UUID userId,
		@Schema(description = "사용자 이름", example = "김위시")
		String name,
		@Schema(description = "이메일", example = "wishconnect@gmail.com")
		String email,
		@Schema(description = "출생년도", example = "2004")
		String birthYear,
		@Schema(description = "거주 지역", example = "서울")
		String region,
		@Schema(description = "프로필 완성도", example = "85")
		int profileCompletionRate,
		@Schema(description = "스크랩한 장학금 수", example = "16")
		long scrappedCount,
		@Schema(description = "지원서 수", example = "6")
		long applicationCount,
		@Schema(description = "완료된 지원서 수", example = "4")
		long completedCount,
		RecommendationCriteria recommendationCriteria
) {

	public record RecommendationCriteria(
			@Schema(description = "학년/학기", example = "3학년")
			String grade,
			@Schema(description = "추천 기준으로 표시할 학점", example = "4.1")
			BigDecimal gpa,
			@Schema(description = "소득분위", example = "3분위")
			String incomeLevel,
			@Schema(description = "관심 장학금 분야", example = "[\"생활비 지원\", \"등록금 지원\"]")
			List<String> interests
	) {
	}
}
