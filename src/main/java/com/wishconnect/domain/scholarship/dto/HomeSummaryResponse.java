package com.wishconnect.domain.scholarship.dto;

/**
 * 홈 화면 요약. matchedCount = 프로필 기준 지원 가능(불충족 조건 없음) 장학금 수,
 * deadlineSoonCount = 마감 임박(D-7 이내) 장학금 수.
 */
public record HomeSummaryResponse(
		long matchedCount,
		long deadlineSoonCount
) {
}
