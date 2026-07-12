package com.wishconnect.domain.scholarship.entity;

/*
raw_scholarship 원본 데이터의 파싱 상태를 표현합니다.(정제 됐는지 안됐는지)
원본 저장 후 정제 테이블(scholarship)로 변환하는 단계에서 상태 추적에 사용합니다.
PENDING  -> 원본 저장됨, 아직 파싱 전, scholarship_id NULL 가능
PARSED   -> 파싱 성공, scholarship_id 있음
FAILED   -> 파싱 실패, scholarship_id NULL 가능
SKIPPED  -> 마감 지난 공고라 정제 저장 안 함, scholarship_id NULL
 */
public enum ParseStatus {
	PENDING,
	PARSED,
	FAILED,
	SKIPPED
}
