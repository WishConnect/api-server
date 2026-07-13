package com.wishconnect.domain.scholarship.entity;

/*
장학금 모집 상태를 표현합니다.
신청 시작일/마감일을 매핑한 뒤 UPCOMING, OPEN, CLOSED 중 하나로 계산해 저장합니다.
 */
public enum RecruitmentStatus {
	UPCOMING,
	OPEN,
	CLOSED
}
