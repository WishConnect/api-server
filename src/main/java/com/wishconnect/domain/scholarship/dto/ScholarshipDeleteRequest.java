package com.wishconnect.domain.scholarship.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

@Schema(description = "장학금 내리기·복원 요청")
public record ScholarshipDeleteRequest(
		@Schema(description = "사유. 내리기는 필수(감사 기록과 목록에 남는다)", example = "장학금이 아닌 선발 결과 안내 공고")
		@Size(max = 500) String reason
) {
}
