package com.wishconnect.domain.application.dto.response;

import java.util.List;

/**
 * ③ 지원서 통합 상세 조회 응답의 문항별 항목.
 *
 * @param seedQuestion AI 인터뷰의 첫 질문 (stepOrder=0). 인터뷰 시작 전이면 null.
 * @param answer       현재 답변 상태. 지원서 생성 직후에도 빈 answer 는 존재하지만
 *                     엔티티 정합성이 깨진 예외적 경우에 한해 null 가능.
 */
public record QuestionDetailResponse(
		Long questionId,
		int order,
		String title,
		String description,
		Integer charLimit,
		QuestionStep currentStep,
		String seedQuestion,
		List<InterviewTurnResponse> interviews,
		AnswerDetailResponse answer
) {
}
