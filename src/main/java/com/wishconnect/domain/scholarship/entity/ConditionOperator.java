package com.wishconnect.domain.scholarship.entity;

/*
scholarship_condition 조건 비교 방식입니다.
현재 공공데이터 매핑은 대부분 문자열 조건이라 EQ를 기본값으로 사용합니다.

장학금 조건 비교 연산자
 *
 * EQ      : 값이 정확히 일치 (=)
 *           예) 성별 = 여성, 학년 = 3학년
 *
 * IN      : 여러 값 중 하나에 포함
 *           예) 학과 ∈ {컴퓨터공학, AI, 소프트웨어}
 *
 * GTE     : 기준값 이상 (>=)
 *           예) 평점 >= 3.5, 이수학점 >= 12
 *
 * LTE     : 기준값 이하 (<=)
 *           예) 소득분위 <= 4, 나이 <= 25
 *
 * BETWEEN : 특정 범위 내
 *           예) 2학년 ~ 4학년, 평점 3.0 ~ 4.5
 *
 * 현재 공공데이터 API는 대부분 문자열 조건으로 제공되므로
 * 현재는 EQ를 주로 사용하며, AI 파싱을 통해 숫자 조건을
 * 추출하면 GTE, LTE, BETWEEN 등을 사용할 수 있다.
 */
public enum ConditionOperator {
	EQ,
	IN,
	GTE,
	LTE,
	BETWEEN
}
