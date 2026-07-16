package com.wishconnect.domain.scholarship.dto;

/*
확인용
동기화 과정에서 어떤 월별 엔드포인트를 몇 건 수집했는지 응답에 담는 DTO입니다.
 */
public record ScholarshipEndpointSyncResponse(
	String endpointDate,
	String description,
	String endpointPath,
	int fetchedCount
) {
}
