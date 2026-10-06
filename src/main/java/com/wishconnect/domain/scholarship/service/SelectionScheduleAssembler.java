package com.wishconnect.domain.scholarship.service;

import com.wishconnect.domain.scholarship.dto.ScholarshipDetailResponse.ScheduleStep;
import com.wishconnect.domain.scholarship.entity.ScholarshipTimeline;
import com.wishconnect.domain.scholarship.entity.TimelineStageCode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 사용자 장학금 상세의 {@code selectionSchedule} 을 조립한다.
 *
 * <ol>
 *   <li>APPLICATION 단계 행이 없고 모집기간이 있으면 모집기간으로 "서류접수" 줄을 맨 앞에 붙인다.
 *       APPLICATION 행이 있으면(트랙별 접수 기간을 직접 넣은 경우) 붙이지 않는다.</li>
 *   <li>그 뒤에 일정 행을 표시 순서대로.</li>
 * </ol>
 *
 * <p>상태는 저장하지 않고 조회할 때 한국 날짜 기준으로 계산한다. 서버 JVM 시간대(UTC)를 따르면
 * 자정~오전 9시 사이에 하루씩 어긋난다.
 */
public final class SelectionScheduleAssembler {

	static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");
	static final String APPLICATION_FALLBACK_TITLE = "서류접수";
	private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy.MM.dd");

	/** 내부 상태. 응답으로 내보낼 때는 {@link #toResponseStatus} 를 거친다. */
	public enum Status { CLOSED, CURRENT, UPCOMING, TBD }

	private SelectionScheduleAssembler() {
	}

	/** 상태 계산 기준일(한국 날짜). */
	public static LocalDate today() {
		return today(Clock.systemUTC());
	}

	static LocalDate today(Clock clock) {
		return LocalDate.now(clock.withZone(SERVICE_ZONE));
	}

	public static List<ScheduleStep> assemble(List<ScholarshipTimeline> timelines, LocalDateTime applicationStartAt,
			LocalDateTime applicationEndAt, LocalDate today) {
		List<ScheduleStep> steps = new ArrayList<>();
		boolean hasApplicationRow = timelines.stream()
				.anyMatch(timeline -> timeline.getStageCode() == TimelineStageCode.APPLICATION);
		if (!hasApplicationRow && (applicationStartAt != null || applicationEndAt != null)) {
			LocalDate start = applicationStartAt == null ? null : applicationStartAt.toLocalDate();
			LocalDate end = applicationEndAt == null ? null : applicationEndAt.toLocalDate();
			steps.add(new ScheduleStep(APPLICATION_FALLBACK_TITLE, formatPeriod(start, end),
					toResponseStatus(periodStatus(start, end, today))));
		}
		for (ScholarshipTimeline timeline : timelines) {
			steps.add(new ScheduleStep(timeline.getTitle(), formatDate(timeline),
					toResponseStatus(status(timeline, today))));
		}
		return steps;
	}

	/** 일정 행의 날짜 문자열. SINGLE "yyyy.MM.dd", RANGE "yyyy.MM.dd ~ yyyy.MM.dd", TBD 는 미정 문구 그대로. */
	static String formatDate(ScholarshipTimeline timeline) {
		return switch (timeline.getDateType()) {
			case SINGLE -> DATE_FORMAT.format(timeline.getStartDate());
			case RANGE -> DATE_FORMAT.format(timeline.getStartDate()) + " ~ " + DATE_FORMAT.format(timeline.getEndDate());
			case TBD -> timeline.getDateText();
		};
	}

	/**
	 * 일정 행 상태.
	 * <ul>
	 *   <li>SINGLE — 기준일 전 CLOSED, 같은 날 CURRENT, 이후 UPCOMING</li>
	 *   <li>RANGE — 끝 &lt; 기준일 CLOSED, 시작 &gt; 기준일 UPCOMING, 그 외 CURRENT</li>
	 *   <li>TBD — TBD</li>
	 * </ul>
	 */
	static Status status(ScholarshipTimeline timeline, LocalDate today) {
		return switch (timeline.getDateType()) {
			case SINGLE -> timeline.getStartDate().isBefore(today) ? Status.CLOSED
					: timeline.getStartDate().isAfter(today) ? Status.UPCOMING : Status.CURRENT;
			case RANGE -> timeline.getEndDate().isBefore(today) ? Status.CLOSED
					: timeline.getStartDate().isAfter(today) ? Status.UPCOMING : Status.CURRENT;
			case TBD -> Status.TBD;
		};
	}

	/** 모집기간 대체 줄의 상태. 예전 규칙 그대로(한쪽 날짜가 없으면 아는 쪽으로만 판단)이고 기준일만 한국 날짜다. */
	static Status periodStatus(LocalDate start, LocalDate end, LocalDate today) {
		if (end != null && end.isBefore(today)) {
			return Status.CLOSED;
		}
		if (start != null && start.isAfter(today)) {
			return Status.UPCOMING;
		}
		return Status.CURRENT;
	}

	/**
	 * 응답으로 내보낼 상태 문자열. <b>상태를 밖으로 내보내는 곳은 여기 하나다.</b>
	 *
	 * <p>프론트가 아직 TBD 를 그리지 못해 당분간 UPCOMING 으로 바꿔 보낸다(미정 일정은 아직 오지 않은 단계다).
	 * 응답 문서(Swagger)에는 TBD 를 미리 정의해 두었다.
	 */
	// TODO 프론트가 TBD 지원 배포 후 이 변환을 제거 (TBD 를 그대로 내보낸다)
	static String toResponseStatus(Status status) {
		return status == Status.TBD ? Status.UPCOMING.name() : status.name();
	}

	/** 모집기간 표시. 한쪽만 있으면 "~yyyy.MM.dd" / "yyyy.MM.dd~". 요약 테이블의 모집기간도 같은 모양을 쓴다. */
	static String formatPeriod(LocalDate start, LocalDate end) {
		if (start == null && end == null) {
			return null;
		}
		if (start == null) {
			return "~" + DATE_FORMAT.format(end);
		}
		if (end == null) {
			return DATE_FORMAT.format(start) + "~";
		}
		return DATE_FORMAT.format(start) + " ~ " + DATE_FORMAT.format(end);
	}
}
