package com.wishconnect.domain.scholarship.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

/**
 * 홈 화면 "이번 달 일정" 달력.
 *
 * <p>한 장학금이 그 달에 시작도 하고 마감도 하면 이벤트가 2건 나온다(START, DEADLINE).
 * 시안이 "7/10 모집 시작", "7/23 마감"처럼 둘을 같은 목록에 섞어 보여주기 때문이다.
 *
 * <p>{@code markedDates} 는 날짜 그리드에 점을 찍기 위한 것이다. {@code events} 를 다시 훑지 않아도
 * 되도록 서버에서 만들어 준다.
 */
@Schema(description = "장학금 모집 시작·마감 달력 응답")
public record ScholarshipCalendarResponse(
		@Schema(example = "2026") int year,
		@Schema(example = "8") int month,
		@Schema(description = "MATCHED, SCRAPPED, ALL 중 조회 범위", example = "MATCHED") CalendarScope scope,
		@Schema(description = "달력 격자에 표시할 점의 날짜 목록") List<LocalDate> markedDates,
		@Schema(description = "모집 시작·마감 이벤트") List<CalendarEvent> events
) {

	@Schema(description = "달력 날짜에 표시할 장학금 일정")
	public record CalendarEvent(
			LocalDate date,
			EventType type,
			Long scholarshipId,
			String title,
			String organization
	) {
	}

	public enum EventType {
		/** 모집 시작 */
		START,
		/** 모집 마감 */
		DEADLINE
	}
}
