package com.wishconnect.domain.scholarship.client;

import java.time.LocalDate;

/*
OAS 문서에서 찾은 월별 장학금 API 엔드포인트 정보를 표현합니다.
path뿐 아니라 날짜와 설명을 함께 보관해 최신순 정렬과 동기화 결과 표시를 쉽게 합니다.
 */
public record ScholarshipEndpoint(
	String path,
	LocalDate date,
	String description
) {

	public String dateText() {
		return date == null ? null : date.toString();
	}
}
