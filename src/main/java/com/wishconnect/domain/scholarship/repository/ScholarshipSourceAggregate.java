package com.wishconnect.domain.scholarship.repository;

/** 관리자 화면의 출처별 품질 집계 결과(인터페이스 프로젝션). */
public interface ScholarshipSourceAggregate {

	String getSource();

	long getTotal();

	long getWithSummary();

	long getWithAmount();

	long getWithHomepageUrl();
}
