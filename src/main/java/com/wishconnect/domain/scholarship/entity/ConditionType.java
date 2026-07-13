package com.wishconnect.domain.scholarship.entity;

/*
scholarship_condition에 저장할 조건 종류입니다.
초기에는 공공데이터 필드를 문자열 조건으로 저장하고, 추천 엔진이 구체화되면 타입을 확장합니다.
 */
public enum ConditionType {

	// 대학 유형 조건입니다. 원본 필드: 대학구분
	UNIVERSITY_TYPE,

	// 전공/학과 계열 조건입니다. 원본 필드: 학과구분
	MAJOR_FIELD,

	// 지원 가능한 학년 또는 학기 조건입니다. 원본 필드: 학년구분
	GRADE_LEVEL,

	// 성적, 이수학점, 평점 등 학업 기준입니다. 원본 필드: 성적기준 상세내용
	ACADEMIC_CRITERIA,

	// 소득 분위, 수급자, 차상위 등 경제 조건입니다. 원본 필드: 소득기준 상세내용
	INCOME_CRITERIA,

	// 본인 또는 보호자의 지역 거주 조건입니다. 원본 필드: 지역거주여부 상세내용
	REGION_RESIDENCY,

	// 대회 입상, 자격증, 봉사활동 등 특수 자격 조건입니다. 원본 필드: 특정자격 상세내용
	SPECIFIC_QUALIFICATION,

	// 휴학생 제외, 중복 수혜 불가 등 지원 제한 조건입니다. 원본 필드: 자격제한 상세내용
	RESTRICTION,

	// 장학금/학자금 지원 유형 조건입니다. 원본 필드: 학자금유형구분
	FINANCIAL_AID_TYPE,

	// 학교장 추천서 등 추천 필요 여부입니다. 원본 필드: 추천필요여부 상세내용
	RECOMMENDATION_REQUIRED
}
