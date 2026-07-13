package com.wishconnect.domain.scholarship.entity;

/*
장학금 출처 구분을 표현합니다.
교내 장학금은 INTERNAL, 공공데이터 등 외부 수집 장학금은 EXTERNAL로 관리합니다.
 */
public enum ScholarshipType {
	INTERNAL,
	EXTERNAL
}
