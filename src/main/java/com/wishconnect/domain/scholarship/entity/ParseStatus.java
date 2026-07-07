package com.wishconnect.domain.scholarship.entity;

/**
 * 원천(raw) 데이터 파싱 상태.
 * ⚠️ 값 확정 필요 — ERD에 값 미정의. 아래는 합리적 추정치.
 */
public enum ParseStatus {
	PENDING,    // 파싱 대기
	SUCCESS,    // 파싱 성공
	FAILED      // 파싱 실패
}
