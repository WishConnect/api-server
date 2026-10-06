package com.wishconnect.domain.scholarship.entity;

/**
 * 선발 일정 행을 누가 만들었는지.
 *
 * <p>지금은 관리자 수기 입력({@link #MANUAL})만 쓴다. LLM·KOSAF 는 자동 추출을 붙일 때를 위해 값만
 * 미리 정해 둔 것이다 — 어떤 배치도 아직 이 테이블에 쓰지 않는다.
 */
public enum TimelineOrigin {
	MANUAL,
	LLM,
	KOSAF
}
