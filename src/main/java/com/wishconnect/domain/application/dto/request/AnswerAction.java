package com.wishconnect.domain.application.dto.request;

/**
 * ⑤ STEP2 답변 관리 API 의 동작 구분.
 *
 * <ul>
 *   <li>DRAFT   — 인터뷰 이력 기반 AI 초안 생성 (essay_answer.ai_draft 저장 + user_content 초기 복사)</li>
 *   <li>SAVE    — 사용자가 편집한 user_content 임시저장</li>
 *   <li>CONFIRM — 문항 완료 확정 (글자수 검증 후 is_completed=true). 전 문항 완료 시 essay 자동 COMPLETED</li>
 * </ul>
 */
public enum AnswerAction {
	DRAFT,
	SAVE,
	CONFIRM
}
