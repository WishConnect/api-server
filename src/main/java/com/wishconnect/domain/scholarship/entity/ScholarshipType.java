package com.wishconnect.domain.scholarship.entity;

/**
 * 장학금 유형.
 * ⚠️ 값 확정 필요 — ERD에 값 미정의. 아래는 합리적 추정치.
 */
public enum ScholarshipType {
	NATIONAL,             // 국가장학금
	UNIVERSITY,           // 교내(대학)
	CORPORATE,            // 기업
	FOUNDATION,           // 재단/법인
	PUBLIC_INSTITUTION,   // 공공기관/지자체
	PRIVATE               // 민간/기타
}
