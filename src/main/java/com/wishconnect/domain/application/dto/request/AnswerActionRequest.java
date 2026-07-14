package com.wishconnect.domain.application.dto.request;

import jakarta.validation.constraints.NotNull;

/**
 * ⑤ PUT /api/v1/applications/{id}/questions/{qid}/answer 요청 바디.
 *
 * @param action      DRAFT / SAVE / CONFIRM (필수)
 * @param userContent SAVE·CONFIRM 시 필수. DRAFT 시에는 무시된다.
 */
public record AnswerActionRequest(
		@NotNull AnswerAction action,
		String userContent
) {
}
