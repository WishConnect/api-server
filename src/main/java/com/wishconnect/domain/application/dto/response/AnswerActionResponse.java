package com.wishconnect.domain.application.dto.response;

/**
 * ⑤ STEP2 답변 관리 API 응답.
 *
 * @param questionId            대상 문항 ID
 * @param charCount             현재 저장된 본문 글자수
 * @param charLimit             문항의 글자수 제한 (nullable)
 * @param isCompleted           이 문항이 완료 확정 상태인지 (CONFIRM 액션 시 true)
 * @param applicationCompleted  이번 CONFIRM 으로 전체 지원서가 COMPLETED 로 자동 전환됐는지
 */
public record AnswerActionResponse(
		Long questionId,
		int charCount,
		Integer charLimit,
		boolean isCompleted,
		boolean applicationCompleted
) {
}
