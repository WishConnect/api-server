package com.wishconnect.domain.application.dto.response;

/**
 * 문항 단위 작성 진행 단계. Notion API 명세서 ③ 응답의 currentStep 필드에 대응.
 * 엔티티에는 저장하지 않고 essay_answer / ai_interview 상태로부터 파생한다.
 */
public enum QuestionStep {

	/** 사전 인터뷰 진행 중 (AI 초안이 아직 생성되지 않은 상태) */
	STEP_1,

	/** 초안 확인·수정 중 (AI 초안이 존재하고 완료 확정 전) */
	STEP_2,

	/** 완료 확정 */
	DONE
}
