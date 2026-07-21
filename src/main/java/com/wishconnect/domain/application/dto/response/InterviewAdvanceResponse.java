package com.wishconnect.domain.application.dto.response;

/**
 * ④ STEP1 사전 인터뷰 대화 응답.
 *
 * @param nextStepOrder       방금 저장된 다음 질문의 stepOrder. 인터뷰가 완료됐다면 null.
 * @param nextQuestion        AI 가 생성한 다음 질문 텍스트. 인터뷰가 완료됐다면 null.
 * @param isInterviewComplete true 이면 클라이언트는 STEP2(초안 확인/수정) 로 진입 안내.
 */
public record InterviewAdvanceResponse(
		Integer nextStepOrder,
		String nextQuestion,
		boolean isInterviewComplete
) {

	public static InterviewAdvanceResponse next(int stepOrder, String question) {
		return new InterviewAdvanceResponse(stepOrder, question, false);
	}

	public static InterviewAdvanceResponse complete() {
		return new InterviewAdvanceResponse(null, null, true);
	}
}
