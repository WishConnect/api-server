package com.wishconnect.domain.scholarship.dto;

/**
 * 홈 - 오늘의 장학금 소식 요약. 노션 명세(GET /api/v1/scholarships/home-summary) 구조.
 * newMatchedCount = 최근 동기화(7일 이내 등록)된 지원 가능 장학금 수("신규" 기준은 팀 확정 전이라 등록일 기준),
 * urgentDeadlineCount = 지원 가능 장학금 중 D-7 이내 마감 수.
 */
public record HomeSummaryResponse(
		long newMatchedCount,
		long urgentDeadlineCount,
		/**
		 * 작성 중인 지원서 수. 시안의 "오늘의 장학금 소식" 카드 세 번째 칸이다.
		 * NOT_STARTED(문항만 준비된 상태)와 IN_PROGRESS 를 합친다 — 사용자 눈에는 둘 다 "쓰다 만 것"이다.
		 */
		long writingApplicationCount,
		boolean hasNewMatched
) {
}
