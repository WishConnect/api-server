package com.wishconnect.domain.application.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * ⑤ PUT /api/v1/applications/{id}/questions/{qid}/answer 요청 바디.
 *
 * @param action      DRAFT / SAVE / CONFIRM (필수)
 * @param userContent SAVE·CONFIRM 시 필수. DRAFT 시에는 무시된다.
 */
@Schema(description = "STEP2 답변 관리 요청. action 으로 세 동작 통합 처리.")
public record AnswerActionRequest(
		@Schema(description = "동작 구분", example = "draft", requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull AnswerAction action,

		@Schema(description = "사용자 편집 본문. SAVE·CONFIRM 시 필수 (DRAFT 시 무시)",
				example = "저는 고등학교 시절 봉사활동을 통해...")
		String userContent
) {
}
