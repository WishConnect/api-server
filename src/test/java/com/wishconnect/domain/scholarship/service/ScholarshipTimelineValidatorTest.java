package com.wishconnect.domain.scholarship.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.wishconnect.domain.scholarship.dto.TimelineFieldErrorResponse;
import com.wishconnect.domain.scholarship.dto.TimelineItemRequest;
import com.wishconnect.domain.scholarship.entity.TimelineDateType;
import com.wishconnect.domain.scholarship.entity.TimelineStageCode;
import com.wishconnect.global.exception.CustomDetailException;
import com.wishconnect.global.exception.ErrorCode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("선발 일정 입력 검증")
class ScholarshipTimelineValidatorTest {

	private static final LocalDate D1 = LocalDate.of(2026, 11, 20);
	private static final LocalDate D2 = LocalDate.of(2026, 11, 25);

	@Test
	@DisplayName("null 은 null(기존 유지), [] 는 빈 목록(모두 삭제)으로 구분한다")
	void distinguishesNullAndEmpty() {
		assertThat(ScholarshipTimelineValidator.normalize(null)).isNull();
		assertThat(ScholarshipTimelineValidator.normalize(List.of())).isEmpty();
	}

	@Test
	@DisplayName("SINGLE 은 startDate 만 보내도 endDate 를 같은 날로 채운다")
	void fillsSingleEndDate() {
		ScholarshipTimelineValidator.Item item = one(new TimelineItemRequest(
				"INTERVIEW", "면접", "SINGLE", D1, null, null, null, null));

		assertThat(item.startDate()).isEqualTo(D1);
		assertThat(item.endDate()).isEqualTo(D1);
	}

	@ParameterizedTest(name = "{0} → {1}")
	@MethodSource("defaultTitles")
	@DisplayName("표준 단계는 표시명이 비어 있으면 기본 표시명으로 채운다")
	void fillsDefaultTitle(String stageCode, String expected) {
		assertThat(one(new TimelineItemRequest(stageCode, "  ", "SINGLE", D1, null, null, null, null)).title())
				.isEqualTo(expected);
	}

	static Stream<Arguments> defaultTitles() {
		return Stream.of(
				Arguments.of("APPLICATION", "서류접수"),
				Arguments.of("DOC_REVIEW", "서류심사"),
				Arguments.of("DOC_RESULT", "서류 발표"),
				Arguments.of("INTERVIEW", "면접"),
				Arguments.of("FINAL_RESULT", "최종 발표"),
				Arguments.of("PAYMENT", "장학금 지급"));
	}

	@Test
	@DisplayName("입력한 표시명은 그대로 쓴다(앞뒤 공백만 정리)")
	void keepsGivenTitle() {
		assertThat(one(new TimelineItemRequest("INTERVIEW", " 2차 면접 ", "SINGLE", D1, null, null, null, null))
				.title()).isEqualTo("2차 면접");
	}

	@Test
	@DisplayName("TBD 는 날짜 없이 문구만, 날짜 형태가 아니면 문구를 버린다")
	void tbdKeepsTextOnly() {
		ScholarshipTimelineValidator.Item tbd = one(new TimelineItemRequest(
				"FINAL_RESULT", null, "TBD", null, null, " 12월 중 예정 ", null, null));
		assertThat(tbd.dateType()).isEqualTo(TimelineDateType.TBD);
		assertThat(tbd.dateText()).isEqualTo("12월 중 예정");
		assertThat(tbd.startDate()).isNull();

		ScholarshipTimelineValidator.Item range = one(new TimelineItemRequest(
				"DOC_REVIEW", null, "RANGE", D1, D1, "남은 문구", null, null));
		assertThat(range.dateText()).isNull();
		assertThat(range.endDate()).isEqualTo(D1);
	}

	@Test
	@DisplayName("같은 단계 중복·단계 사이 날짜 역순은 오류가 아니다(화면 경고만)")
	void duplicatesAndOrderAreWarningsOnly() {
		List<ScholarshipTimelineValidator.Item> items = ScholarshipTimelineValidator.normalize(List.of(
				new TimelineItemRequest("APPLICATION", "A트랙 접수", "RANGE", D1, D2, null, null, null),
				new TimelineItemRequest("APPLICATION", "B트랙 접수", "SINGLE", D1.minusDays(30), null, null, null, null)));

		assertThat(items).extracting(ScholarshipTimelineValidator.Item::stageCode)
				.containsExactly(TimelineStageCode.APPLICATION, TimelineStageCode.APPLICATION);
	}

	@Test
	@DisplayName("최대 10행까지 받고 11행이면 TIMELINE_TOO_MANY")
	void rejectsMoreThanTen() {
		List<TimelineItemRequest> ten = new ArrayList<>(Collections.nCopies(10,
				new TimelineItemRequest("CUSTOM", "단계", "SINGLE", D1, null, null, null, null)));
		assertThat(ScholarshipTimelineValidator.normalize(ten)).hasSize(10);

		ten.add(ten.get(0));
		assertError(ten, ErrorCode.TIMELINE_TOO_MANY, null, "timeline", 10);
	}

	@ParameterizedTest(name = "{1}")
	@MethodSource("invalidItems")
	@DisplayName("위반마다 고유 ErrorCode 와 행 번호·필드를 돌려준다")
	void reportsFieldErrors(TimelineItemRequest item, ErrorCode code, String field, Integer max) {
		List<TimelineItemRequest> items = new ArrayList<>();
		items.add(new TimelineItemRequest("DOC_RESULT", null, "SINGLE", D1, null, null, null, null));
		items.add(item);
		assertError(items, code, 1, field, max);
	}

	static Stream<Arguments> invalidItems() {
		String long51 = "가".repeat(51);
		return Stream.of(
				Arguments.of(null, ErrorCode.TIMELINE_STAGE_CODE_INVALID, "stageCode", null),
				Arguments.of(item(null, "t", "SINGLE", D1, null, null), ErrorCode.TIMELINE_STAGE_CODE_INVALID,
						"stageCode", null),
				Arguments.of(item("SCHOOL_VISIT", "t", "SINGLE", D1, null, null), ErrorCode.TIMELINE_STAGE_CODE_INVALID,
						"stageCode", null),
				Arguments.of(item("INTERVIEW", null, null, D1, null, null), ErrorCode.TIMELINE_DATE_TYPE_INVALID,
						"dateType", null),
				Arguments.of(item("INTERVIEW", null, "DAILY", D1, null, null), ErrorCode.TIMELINE_DATE_TYPE_INVALID,
						"dateType", null),
				Arguments.of(item("CUSTOM", " ", "SINGLE", D1, null, null), ErrorCode.TIMELINE_TITLE_REQUIRED,
						"title", null),
				Arguments.of(item("CUSTOM", long51, "SINGLE", D1, null, null), ErrorCode.TIMELINE_FIELD_TOO_LONG,
						"title", 50),
				Arguments.of(item("CUSTOM", "t", "TBD", null, null, "가".repeat(101)),
						ErrorCode.TIMELINE_FIELD_TOO_LONG, "dateText", 100),
				Arguments.of(new TimelineItemRequest("CUSTOM", "t", "SINGLE", D1, null, null, "가".repeat(201), null),
						ErrorCode.TIMELINE_FIELD_TOO_LONG, "note", 200),
				Arguments.of(new TimelineItemRequest("CUSTOM", "t", "SINGLE", D1, null, null, null, "가".repeat(2001)),
						ErrorCode.TIMELINE_FIELD_TOO_LONG, "evidence", 2000),
				Arguments.of(item("INTERVIEW", null, "SINGLE", null, null, null), ErrorCode.TIMELINE_DATE_REQUIRED,
						"startDate", null),
				Arguments.of(item("INTERVIEW", null, "SINGLE", null, D1, null), ErrorCode.TIMELINE_DATE_REQUIRED,
						"startDate", null),
				Arguments.of(item("INTERVIEW", null, "SINGLE", D1, D2, null), ErrorCode.TIMELINE_SINGLE_DATE_MISMATCH,
						"endDate", null),
				Arguments.of(item("INTERVIEW", null, "RANGE", null, D2, null), ErrorCode.TIMELINE_DATE_REQUIRED,
						"startDate", null),
				Arguments.of(item("INTERVIEW", null, "RANGE", D1, null, null), ErrorCode.TIMELINE_DATE_REQUIRED,
						"endDate", null),
				Arguments.of(item("INTERVIEW", null, "RANGE", D2, D1, null), ErrorCode.TIMELINE_RANGE_REVERSED,
						"endDate", null),
				Arguments.of(item("INTERVIEW", null, "TBD", D1, null, "미정"), ErrorCode.TIMELINE_TBD_DATE_NOT_ALLOWED,
						"startDate", null),
				Arguments.of(item("INTERVIEW", null, "TBD", null, D1, "미정"), ErrorCode.TIMELINE_TBD_DATE_NOT_ALLOWED,
						"endDate", null),
				Arguments.of(item("INTERVIEW", null, "TBD", null, null, "  "), ErrorCode.TIMELINE_DATE_TEXT_REQUIRED,
						"dateText", null));
	}

	@ParameterizedTest
	@EnumSource(TimelineStageCode.class)
	@DisplayName("CUSTOM 만 기본 표시명이 없다")
	void onlyCustomHasNoDefault(TimelineStageCode code) {
		assertThat(code.defaultTitle() == null).isEqualTo(code == TimelineStageCode.CUSTOM);
	}

	private static TimelineItemRequest item(String stageCode, String title, String dateType, LocalDate start,
			LocalDate end, String dateText) {
		return new TimelineItemRequest(stageCode, title, dateType, start, end, dateText, null, null);
	}

	private static ScholarshipTimelineValidator.Item one(TimelineItemRequest request) {
		return ScholarshipTimelineValidator.normalize(List.of(request)).get(0);
	}

	private static void assertError(List<TimelineItemRequest> items, ErrorCode code, Integer index, String field,
			Integer max) {
		CustomDetailException e = catchThrowableOfType(
				() -> ScholarshipTimelineValidator.normalize(items), CustomDetailException.class);
		assertThat(e).as("예외가 나야 한다").isNotNull();
		assertThat(e.getErrorCode()).isEqualTo(code);
		assertThat(e.getDetail()).isEqualTo(new TimelineFieldErrorResponse(index, field, max));
	}
}
