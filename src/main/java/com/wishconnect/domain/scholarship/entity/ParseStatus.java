package com.wishconnect.domain.scholarship.entity;

/*
raw_scholarship 원본 데이터의 파싱 상태를 표현합니다.
원본 저장 후 정제 테이블(scholarship)로 변환하는 단계에서 상태 추적에 사용합니다.
 */
public enum ParseStatus {
	PENDING,
	PARSED,
	FAILED,
	SKIPPED
}
