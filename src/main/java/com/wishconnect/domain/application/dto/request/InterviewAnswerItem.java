package com.wishconnect.domain.application.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * ④ STEP1 사전 인터뷰 답변 한 건.
 *
 * @param stepOrder  답변 대상 질문의 stepOrder (0부터 시작). 인터뷰 시작 응답에서 받은 값.
 * @param answerText 사용자 답변 텍스트. 비어 있으면 해당 항목은 저장하지 않고 건너뛴다.
 */
@Schema(description = "사전 인터뷰 답변 한 건.")
public record InterviewAnswerItem(
		@Schema(description = "답변 대상 질문의 stepOrder (0부터 시작).", example = "0")
		Integer stepOrder,

		@Schema(description = "사용자 답변 텍스트. 비어 있으면 저장하지 않고 건너뛴다.",
				example = "고등학교 봉사활동에서 협업의 가치를 배웠습니다.")
		String answerText
) {
}
