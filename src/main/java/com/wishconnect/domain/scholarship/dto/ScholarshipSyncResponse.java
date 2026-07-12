package com.wishconnect.domain.scholarship.dto;

import java.util.List;

/*
postman 응답 확인용
장학금 동기화 API의 전체 실행 결과를 내려주는 응답 DTO입니다.
전체 수집/저장/실패 건수와 엔드포인트별 수집 내역을 함께 제공합니다.
 */
public record ScholarshipSyncResponse(
	int fetchedCount,
	int savedCount,
	int failedCount,
	List<ScholarshipEndpointSyncResponse> endpoints
) {
}
