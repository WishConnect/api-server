package com.wishconnect.domain.application.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * ④ STEP1 사전 인터뷰 대화 요청 바디.
 *
 * <p>인터뷰 부트스트랩(첫 호출)에는 두 필드 모두 null 로 보내면 된다.
 * 계속 진행 시에는 클라이언트가 현재 응답 중인 stepOrder 와 답변을 담아 보낸다.
 *
 * @param stepOrder  답변 대상 인터뷰의 stepOrder. 부트스트랩 시 null.
 * @param answerText 사용자의 답변 텍스트. 부트스트랩 시 null.
 */
@Schema(description = "STEP1 인터뷰 대화 요청. 부트스트랩(첫 호출)이면 두 필드 모두 null.")
public record InterviewAnswerRequest(
		@Schema(description = "답변 대상 인터뷰의 stepOrder. 부트스트랩 시 null.", example = "0")
		Integer stepOrder,

		@Schema(description = "사용자 답변 텍스트. 부트스트랩 시 null.",
				example = "고등학교 봉사활동에서 협업의 가치를 배웠습니다.")
		String answerText
) {
}
