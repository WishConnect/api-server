package com.wishconnect.domain.scholarship.entity;

/**
 * 장학금 지원 자격 조건의 종류.
 * ⚠️ 값 확정 필요 — ERD에 값 미정의. 아래는 합리적 추정치.
 */
public enum ConditionType {
	GPA,            // 성적(학점)
	INCOME_LEVEL,   // 소득분위
	GRADE,          // 학년
	REGION,         // 지역
	MAJOR,          // 전공/계열
	SCHOOL,         // 학교
	AGE,            // 나이
	FAMILY_TYPE,    // 가구유형
	ENROLLMENT      // 재학상태
}
