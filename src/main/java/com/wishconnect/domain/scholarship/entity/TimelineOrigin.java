package com.wishconnect.domain.scholarship.entity;

/** 선발 일정의 출처. MANUAL은 보존하고, LLM은 원문 검증 후 자동 수집에서 갱신한다. */
public enum TimelineOrigin {
	MANUAL,
	LLM,
	KOSAF
}
