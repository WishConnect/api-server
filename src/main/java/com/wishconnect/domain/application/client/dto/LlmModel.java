package com.wishconnect.domain.application.client.dto;

/**
 * LLM 호출 목적 구분.
 * 실제 Claude 모델 ID는 {@link com.wishconnect.domain.application.config.LlmProperties} 에서 매핑된다.
 */
public enum LlmModel {

	/** STEP1 사전 인터뷰 세부 질문 생성 (기본: claude-haiku-4-5) */
	INTERVIEW,

	/** STEP2 자기소개서 초안 생성 (기본: claude-sonnet-5) */
	DRAFT,

	/** 인사이트 콘텐츠 요약/분류 (기본: claude-haiku-4-5) */
	SUMMARY,

	/**
	 * 대학 장학공지 본문 구조화 파싱 (기본: claude-haiku-4-5).
	 * 공고 한 건당 한 번 호출하므로 건수가 많다. 저가 모델을 쓰고 응답 길이를 짧게 유지한다.
	 */
	PARSING
}
