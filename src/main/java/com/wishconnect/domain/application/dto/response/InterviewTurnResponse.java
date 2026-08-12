package com.wishconnect.domain.application.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 사전 인터뷰 질문 한 건과 그에 대한 답변 상태.
 *
 * <p>④ 응답의 {@code questions} 배열과 ③ 통합 상세 조회의 {@code interviews} 배열에서
 * 같은 구조로 쓰인다.
 *
 * @param stepOrder    질문 순번 (0부터). 답변을 보낼 때 이 값을 그대로 실어 보낸다
 * @param questionText 질문 본문
 * @param answerText   사용자 답변. 아직 작성 전이면 null
 */
@Schema(description = "사전 인터뷰 질문 한 건과 답변 상태.")
public record InterviewTurnResponse(
		@Schema(description = "질문 순번 (0부터 시작). 답변 저장 요청에 이 값을 그대로 실어 보낸다.",
				example = "0")
		int stepOrder,

		@Schema(description = "질문 본문. 한 문장, 60자 이내로 생성된다.",
				example = "가장 기억에 남는 경험은 무엇인가요?")
		String questionText,

		@Schema(description = "사용자 답변. 아직 작성 전이면 null.",
				example = "동아리 회장을 맡아 예산 문제를 해결했던 경험이 있습니다.")
		String answerText
) {
}
