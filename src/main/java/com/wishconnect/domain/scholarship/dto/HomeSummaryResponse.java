package com.wishconnect.domain.scholarship.dto;

/**
 * 홈 - 오늘의 장학금 소식 요약. 노션 명세(GET /api/v1/scholarships/home-summary) 구조.
 * newMatchedCount = 최근 동기화(7일 이내 등록)된 지원 가능 장학금 수("신규" 기준은 팀 확정 전이라 등록일 기준),
 * urgentDeadlineCount = 지원 가능 장학금 중 D-7 이내 마감 수.
 */
public record HomeSummaryResponse(
		long newMatchedCount,
		long urgentDeadlineCount,
		boolean hasNewMatched
) {
}
