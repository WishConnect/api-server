package com.wishconnect.domain.scholarship.entity;

/*
장학금 출처 구분을 표현합니다.
교내 장학금은 INTERNAL, 공공데이터 등 외부 수집 장학금은 EXTERNAL로 관리합니다.
근로 대가로 지급되는 근로장학금(국가근로/교내근로/일반근로)은 성격이 달라 WORK_STUDY로 분리합니다.
모집 단위가 학과·부서이고 학기마다 반복되어, 일반 장학공고와 같은 목록에 섞이면 큐레이팅 품질이 떨어집니다.
 */
public enum ScholarshipType {
	INTERNAL,
	EXTERNAL,
	WORK_STUDY
}
