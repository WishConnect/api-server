package com.wishconnect.domain.scholarship.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.wishconnect.domain.scholarship.dto.ScholarshipDetailResponse.ScheduleStep;
import com.wishconnect.domain.scholarship.entity.ScholarshipTimeline;
import com.wishconnect.domain.scholarship.entity.TimelineDateType;
import com.wishconnect.domain.scholarship.entity.TimelineOrigin;
import com.wishconnect.domain.scholarship.entity.TimelineStageCode;
import com.wishconnect.domain.scholarship.service.SelectionScheduleAssembler.Status;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("사용자 상세 selectionSchedule 조립")
class SelectionScheduleAssemblerTest {

	private static final LocalDate TODAY = LocalDate.of(2026, 10, 6);
	private static final LocalDateTime APPLY_START = LocalDateTime.of(2026, 10, 1, 0, 0);
	private static final LocalDateTime APPLY_END = LocalDateTime.of(2026, 10, 15, 23, 59);

	private static ScholarshipTimeline single(TimelineStageCode code, String title, LocalDate date) {
		return row(code, title, TimelineDateType.SINGLE, date, date, null);
	}

	private static ScholarshipTimeline range(TimelineStageCode code, String title, LocalDate start, LocalDate end) {
		return row(code, title, TimelineDateType.RANGE, start, end, null);
	}

	private static ScholarshipTimeline tbd(TimelineStageCode code, String title, String text) {
		return row(code, title, TimelineDateType.TBD, null, null, text);
	}

	private static ScholarshipTimeline row(TimelineStageCode code, String title, TimelineDateType type,
			LocalDate start, LocalDate end, String text) {
		return ScholarshipTimeline.builder().stageCode(code).title(title).dateType(type).startDate(start)
				.endDate(end).dateText(text).origin(TimelineOrigin.MANUAL).build();
	}

	@Nested
	@DisplayName("줄 구성")
	class Composition {

		@Test
		@DisplayName("일정이 없으면 모집기간 \"서류접수\" 한 줄(기존 동작)")
		void fallbackOnly() {
			List<ScheduleStep> steps = SelectionScheduleAssembler.assemble(List.of(), APPLY_START, APPLY_END, TODAY);

			assertThat(steps).containsExactly(new ScheduleStep("서류접수", "2026.10.01 ~ 2026.10.15", "CURRENT"));
		}

		@Test
		@DisplayName("일정도 모집기간도 없으면 빈 목록")
		void empty() {
			assertThat(SelectionScheduleAssembler.assemble(List.of(), null, null, TODAY)).isEmpty();
		}

		@Test
		@DisplayName("접수 단계 행이 없으면 대체 줄을 맨 앞에 붙이고 일정을 순서대로 잇는다")
		void fallbackThenRows() {
			List<ScheduleStep> steps = SelectionScheduleAssembler.assemble(List.of(
					single(TimelineStageCode.DOC_RESULT, "서류 발표", LocalDate.of(2026, 10, 20)),
					single(TimelineStageCode.INTERVIEW, "면접", LocalDate.of(2026, 10, 25)),
					tbd(TimelineStageCode.FINAL_RESULT, "최종 발표", "11월 중 예정")),
					APPLY_START, APPLY_END, TODAY);

			assertThat(steps).extracting(ScheduleStep::step).containsExactly("서류접수", "서류 발표", "면접", "최종 발표");
		}

		@Test
		@DisplayName("접수 단계 행이 있으면(트랙별 접수 직접 입력) 대체 줄을 붙이지 않는다")
		void noFallbackWhenApplicationRowExists() {
			List<ScheduleStep> steps = SelectionScheduleAssembler.assemble(List.of(
					range(TimelineStageCode.APPLICATION, "A트랙 접수", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 10)),
					range(TimelineStageCode.APPLICATION, "B트랙 접수", LocalDate.of(2026, 10, 11), LocalDate.of(2026, 10, 20)),
					single(TimelineStageCode.FINAL_RESULT, "최종 발표", LocalDate.of(2026, 11, 1))),
					APPLY_START, APPLY_END, TODAY);

			assertThat(steps).extracting(ScheduleStep::step).containsExactly("A트랙 접수", "B트랙 접수", "최종 발표");
		}

		@Test
		@DisplayName("모집기간이 없으면 접수 행이 없어도 대체 줄을 만들지 않는다")
		void noFallbackWithoutPeriod() {
			List<ScheduleStep> steps = SelectionScheduleAssembler.assemble(List.of(
					single(TimelineStageCode.INTERVIEW, "면접", LocalDate.of(2026, 10, 25))), null, null, TODAY);

			assertThat(steps).extracting(ScheduleStep::step).containsExactly("면접");
		}
	}

	@Nested
	@DisplayName("날짜 문자열")
	class DateText {

		@Test
		@DisplayName("SINGLE yyyy.MM.dd / RANGE yyyy.MM.dd ~ yyyy.MM.dd / TBD 는 문구 그대로")
		void formats() {
			List<ScheduleStep> steps = SelectionScheduleAssembler.assemble(List.of(
					single(TimelineStageCode.DOC_RESULT, "서류 발표", LocalDate.of(2026, 10, 20)),
					range(TimelineStageCode.INTERVIEW, "면접", LocalDate.of(2026, 10, 25), LocalDate.of(2026, 10, 27)),
					tbd(TimelineStageCode.FINAL_RESULT, "최종 발표", "12월 중 예정")),
					null, null, TODAY);

			assertThat(steps).extracting(ScheduleStep::date)
					.containsExactly("2026.10.20", "2026.10.25 ~ 2026.10.27", "12월 중 예정");
		}

		@Test
		@DisplayName("모집기간 대체 줄은 한쪽 날짜만 있으면 열린 기간으로 쓴다(기존 모양)")
		void openPeriod() {
			assertThat(SelectionScheduleAssembler.assemble(List.of(), null, APPLY_END, TODAY).get(0).date())
					.isEqualTo("~2026.10.15");
			assertThat(SelectionScheduleAssembler.assemble(List.of(), APPLY_START, null, TODAY).get(0).date())
					.isEqualTo("2026.10.01~");
		}
	}

	@Nested
	@DisplayName("상태 계산")
	class StatusCalc {

		@Test
		@DisplayName("SINGLE: 전날 CLOSED, 당일 CURRENT, 다음날 UPCOMING")
		void single() {
			assertThat(SelectionScheduleAssembler.status(
					SelectionScheduleAssemblerTest.single(TimelineStageCode.INTERVIEW, "면접", TODAY.minusDays(1)), TODAY))
					.isEqualTo(Status.CLOSED);
			assertThat(SelectionScheduleAssembler.status(
					SelectionScheduleAssemblerTest.single(TimelineStageCode.INTERVIEW, "면접", TODAY), TODAY))
					.isEqualTo(Status.CURRENT);
			assertThat(SelectionScheduleAssembler.status(
					SelectionScheduleAssemblerTest.single(TimelineStageCode.INTERVIEW, "면접", TODAY.plusDays(1)), TODAY))
					.isEqualTo(Status.UPCOMING);
		}

		@Test
		@DisplayName("RANGE: 끝이 어제면 CLOSED, 끝·시작이 오늘이면 CURRENT, 시작이 내일이면 UPCOMING")
		void range() {
			assertThat(status(TODAY.minusDays(5), TODAY.minusDays(1))).isEqualTo(Status.CLOSED);
			assertThat(status(TODAY.minusDays(5), TODAY)).isEqualTo(Status.CURRENT);
			assertThat(status(TODAY, TODAY.plusDays(5))).isEqualTo(Status.CURRENT);
			assertThat(status(TODAY.minusDays(1), TODAY.plusDays(1))).isEqualTo(Status.CURRENT);
			assertThat(status(TODAY.plusDays(1), TODAY.plusDays(5))).isEqualTo(Status.UPCOMING);
		}

		private Status status(LocalDate start, LocalDate end) {
			return SelectionScheduleAssembler.status(
					SelectionScheduleAssemblerTest.range(TimelineStageCode.DOC_REVIEW, "서류심사", start, end), TODAY);
		}

		@Test
		@DisplayName("TBD 는 내부 상태 TBD — 끝 날짜만 있는 미래 단계·날짜 없는 단계가 CURRENT 로 나가던 문제 해소")
		void tbd() {
			assertThat(SelectionScheduleAssembler.status(
					SelectionScheduleAssemblerTest.tbd(TimelineStageCode.FINAL_RESULT, "최종 발표", "미정"), TODAY))
					.isEqualTo(Status.TBD);
		}

		@Test
		@DisplayName("모집기간 대체 줄은 기존 규칙(한쪽 날짜로만 판단)을 유지한다")
		void fallbackRule() {
			assertThat(SelectionScheduleAssembler.periodStatus(null, TODAY.minusDays(1), TODAY)).isEqualTo(Status.CLOSED);
			assertThat(SelectionScheduleAssembler.periodStatus(null, TODAY, TODAY)).isEqualTo(Status.CURRENT);
			assertThat(SelectionScheduleAssembler.periodStatus(TODAY.plusDays(1), null, TODAY)).isEqualTo(Status.UPCOMING);
			assertThat(SelectionScheduleAssembler.periodStatus(TODAY, null, TODAY)).isEqualTo(Status.CURRENT);
		}

		@Test
		@DisplayName("기준일은 한국 날짜다 — UTC 2026-10-05 15:30 은 한국 10-06")
		void todayIsSeoulDate() {
			Clock utcEvening = Clock.fixed(Instant.parse("2026-10-05T15:30:00Z"), ZoneOffset.UTC);
			Clock utcMorning = Clock.fixed(Instant.parse("2026-10-05T14:59:59Z"), ZoneOffset.UTC);

			assertThat(SelectionScheduleAssembler.today(utcEvening)).isEqualTo(LocalDate.of(2026, 10, 6));
			assertThat(SelectionScheduleAssembler.today(utcMorning)).isEqualTo(LocalDate.of(2026, 10, 5));
		}

		@Test
		@DisplayName("한국 날짜 경계: UTC 로는 전날이어도 한국 당일 발표는 CURRENT")
		void boundaryUsesSeoulDate() {
			LocalDate seoulToday = SelectionScheduleAssembler.today(
					Clock.fixed(Instant.parse("2026-10-05T16:00:00Z"), ZoneOffset.UTC));
			List<ScheduleStep> steps = SelectionScheduleAssembler.assemble(List.of(
					SelectionScheduleAssemblerTest.single(TimelineStageCode.DOC_RESULT, "서류 발표", LocalDate.of(2026, 10, 6))),
					null, null, seoulToday);

			assertThat(steps.get(0).status()).isEqualTo("CURRENT");
		}
	}

	@Nested
	@DisplayName("응답 변환")
	class ResponseStatus {

		@Test
		@DisplayName("TBD 는 당분간 UPCOMING 으로 내보내고, 나머지는 그대로")
		void tbdBecomesUpcoming() {
			assertThat(SelectionScheduleAssembler.toResponseStatus(Status.TBD)).isEqualTo("UPCOMING");
			assertThat(SelectionScheduleAssembler.toResponseStatus(Status.CLOSED)).isEqualTo("CLOSED");
			assertThat(SelectionScheduleAssembler.toResponseStatus(Status.CURRENT)).isEqualTo("CURRENT");
			assertThat(SelectionScheduleAssembler.toResponseStatus(Status.UPCOMING)).isEqualTo("UPCOMING");
		}

		@Test
		@DisplayName("조립 결과에도 TBD 가 UPCOMING 으로, 날짜에는 미정 문구가 나간다")
		void assembledTbd() {
			List<ScheduleStep> steps = SelectionScheduleAssembler.assemble(List.of(
					SelectionScheduleAssemblerTest.tbd(TimelineStageCode.FINAL_RESULT, "최종 발표", "12월 중 예정")),
					null, null, TODAY);

			assertThat(steps).containsExactly(new ScheduleStep("최종 발표", "12월 중 예정", "UPCOMING"));
		}
	}
}
