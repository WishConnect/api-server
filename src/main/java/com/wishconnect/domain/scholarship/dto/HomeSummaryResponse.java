package com.wishconnect.domain.scholarship.dto;

/**
 * 홈 화면(피그마 "홈_모든 정보 입력") 상단 영역을 한 번에 채우는 응답.
 *
 * <p>화면 구성은 인사말 한 줄, "새로운 맞춤 장학금이 등록되었어요" 배너,
 * 그리고 "오늘의 장학금 소식" 카드 4칸이다. 4칸은 각각
 * {@link #newMatchedCount} / {@link #urgentDeadlineCount} /
 * {@link #writingApplicationCount} / {@link #newInsightCount} 에 대응한다.
 */
public record HomeSummaryResponse(
		/** 인사말("안녕하세요, ○○님!")에 쓰는 이름. 홈만 그리려고 회원 조회를 또 하지 않도록 함께 준다. */
		String userName,
		/** 최근 7일 이내 등록된 지원 가능 장학금 수. */
		long newMatchedCount,
		/** 지원 가능 장학금 중 D-7 이내 마감 수. */
		long urgentDeadlineCount,
		/**
		 * 작성 중인 지원서 수. NOT_STARTED(문항만 준비된 상태)와 IN_PROGRESS 를 합친다
		 * — 사용자 눈에는 둘 다 "쓰다 만 것"이다.
		 */
		long writingApplicationCount,
		/**
		 * 최근 7일 이내 수집된 인사이트 수.
		 *
		 * <p>"사용자가 아직 안 본 글"이 아니라 "최근에 새로 들어온 글"이다. 열람 이력을
		 * 남기지 않아 개인별 미열람 수를 셀 수 없다. 나머지 세 칸이 모두 최근 7일 기준이라
		 * 같은 창을 쓴다. 원문 작성일({@code publishedAt})이 아니라 수집 시각 기준인데,
		 * 몇 년 전 블로그 글이 오늘 수집되는 일이 흔해 원문 작성일로 세면 늘 0 이 되기 때문이다.
		 */
		long newInsightCount,
		/** 상단 배너 노출 여부. */
		boolean hasNewMatched
) {
}
