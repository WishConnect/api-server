package com.wishconnect.domain.scholarship.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wishconnect.domain.scholarship.service.ScholarshipChangeFields.Kind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("감사 스냅샷 필드 비교")
class ScholarshipChangeFieldsTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	private JsonNode json(String value) throws Exception {
		return objectMapper.readTree(value);
	}

	@Test
	@DisplayName("날짜는 초 단위로 비교한다 — 저장 정밀도 차이를 변경으로 보지 않는다")
	void truncatesDates() throws Exception {
		JsonNode a = json("{\"applicationEndAt\":\"2026-08-27T23:59:00.123456\",\"deletedAt\":null}");
		JsonNode b = json("{\"applicationEndAt\":\"2026-08-27T23:59\",\"deletedAt\":null}");

		assertThat(ScholarshipChangeFields.changed(Kind.ADMIN_SNAPSHOT, a, b)).isEmpty();
	}

	@Test
	@DisplayName("내리기는 deletedAt 시각이 아니라 삭제 여부로 본다")
	void deletionIsBoolean() throws Exception {
		JsonNode live = json("{\"deletedAt\":null}");
		JsonNode deleted = json("{\"deletedAt\":\"2026-09-29T07:00:00\"}");

		assertThat(ScholarshipChangeFields.changed(Kind.ADMIN_SNAPSHOT, live, deleted)).containsExactly("deleted");
		assertThat(ScholarshipChangeFields.summarize(Kind.ADMIN_SNAPSHOT, live, deleted))
				.isEqualTo("변경: 내리기(삭제) 상태(false→true)");
	}

	@Test
	@DisplayName("통합 수정은 조건 행 ID 가 바뀌어도 내용이 같으면 변경이 아니다")
	void ignoresConditionIds() throws Exception {
		JsonNode a = json("""
				{"scholarship":{"title":"A"},"conditions":[{"id":1,"conditionType":"GRADE_LEVEL",
				 "refs":[{"refId":2,"refCode":null},{"refId":1,"refCode":null}]}],"documents":[]}""");
		JsonNode b = json("""
				{"scholarship":{"title":"A"},"conditions":[{"id":9,"conditionType":"GRADE_LEVEL",
				 "refs":[{"refId":1,"refCode":null},{"refId":2,"refCode":null}]}],"documents":[]}""");

		assertThat(ScholarshipChangeFields.kindOf(a)).isEqualTo(Kind.AGGREGATE);
		assertThat(ScholarshipChangeFields.changed(Kind.AGGREGATE, a, b)).isEmpty();
	}

	@Test
	@DisplayName("요약에 모집 상태 전후 값과 조건 건수가 보인다")
	void summarizesStatusAndConditions() throws Exception {
		JsonNode before = json("""
				{"scholarship":{"title":"A","recruitmentStatus":"OPEN"},
				 "conditions":[{"id":1},{"id":2}],"documents":[]}""");
		JsonNode after = json("""
				{"scholarship":{"title":"B","recruitmentStatus":"CLOSED"},"conditions":[{"id":3}],"documents":[]}""");

		assertThat(ScholarshipChangeFields.summarize(Kind.AGGREGATE, before, after))
				.isEqualTo("변경: 제목, 모집 상태(OPEN→CLOSED), 자격·우대 조건(2→1건)");
	}

	private static final String ROW_A = "{\"id\":1,\"stageCode\":\"DOC_RESULT\",\"title\":\"서류 발표\",\"dateType\":\"SINGLE\","
			+ "\"startDate\":\"2026-11-01\",\"endDate\":\"2026-11-01\",\"origin\":\"MANUAL\",\"displayOrder\":0}";
	private static final String ROW_B = "{\"id\":2,\"stageCode\":\"INTERVIEW\",\"title\":\"면접\",\"dateType\":\"SINGLE\","
			+ "\"startDate\":\"2026-11-10\",\"endDate\":\"2026-11-10\",\"origin\":\"MANUAL\",\"displayOrder\":1}";
	private static final String ROW_C = "{\"id\":3,\"stageCode\":\"FINAL_RESULT\",\"title\":\"최종 발표\",\"dateType\":\"TBD\","
			+ "\"dateText\":\"12월 중\",\"origin\":\"MANUAL\",\"displayOrder\":2}";

	private JsonNode aggregate(boolean periodLocked, String... rows) throws Exception {
		return json("{\"scholarship\":{\"title\":\"A\",\"periodLocked\":" + periodLocked + "},\"conditions\":[],"
				+ "\"documents\":[],\"timeline\":[" + String.join(",", rows) + "]}");
	}

	@Test
	@DisplayName("일정은 행 ID 가 바뀌어도(저장마다 새로 만듦) 내용이 같으면 변경이 아니다")
	void ignoresTimelineIds() throws Exception {
		JsonNode before = aggregate(false, ROW_A);
		JsonNode after = aggregate(false, ROW_A.replace("\"id\":1", "\"id\":99"));

		assertThat(ScholarshipChangeFields.changed(Kind.AGGREGATE, before, after)).isEmpty();
	}

	@Test
	@DisplayName("일정 변경은 \"선발 일정 N건 변경\"으로 요약한다 — 고침 1건, 하나 빼고 둘 넣으면 2건")
	void summarizesTimelineRows() throws Exception {
		JsonNode before = aggregate(false, ROW_A, ROW_B);

		assertThat(ScholarshipChangeFields.summarize(Kind.AGGREGATE, before,
				aggregate(false, ROW_A, ROW_B.replace("2026-11-10", "2026-11-12"))))
				.isEqualTo("변경: 선발 일정 1건 변경");
		assertThat(ScholarshipChangeFields.summarize(Kind.AGGREGATE, before,
				aggregate(false, ROW_A, ROW_C, ROW_C.replace("최종 발표", "장학금 지급"))))
				.isEqualTo("변경: 선발 일정 2건 변경");
		assertThat(ScholarshipChangeFields.summarize(Kind.AGGREGATE, before, aggregate(false)))
				.isEqualTo("변경: 선발 일정 2건 변경");
	}

	@Test
	@DisplayName("순서만 바뀌면 \"선발 일정 순서 변경\"")
	void summarizesTimelineReorder() throws Exception {
		JsonNode before = aggregate(false, ROW_A, ROW_B);
		JsonNode after = aggregate(false, ROW_B.replace("\"displayOrder\":1", "\"displayOrder\":0"),
				ROW_A.replace("\"displayOrder\":0", "\"displayOrder\":1"));

		assertThat(ScholarshipChangeFields.changed(Kind.AGGREGATE, before, after)).containsExactly("timeline");
		assertThat(ScholarshipChangeFields.summarize(Kind.AGGREGATE, before, after))
				.isEqualTo("변경: 선발 일정 순서 변경");
	}

	@Test
	@DisplayName("모집기간 수기 고정은 전후 값을 보여 준다")
	void summarizesPeriodLocked() throws Exception {
		assertThat(ScholarshipChangeFields.summarize(Kind.AGGREGATE, aggregate(false), aggregate(true)))
				.isEqualTo("변경: 모집기간 수기 고정(false→true)");
	}

	@Test
	@DisplayName("옛 스냅샷(일정·고정 키 없음)과 비교하면 일정·고정은 바뀐 필드로 치지 않는다")
	void oldSnapshotDoesNotReportTimeline() throws Exception {
		JsonNode old = json("{\"scholarship\":{\"title\":\"A\"},\"conditions\":[],\"documents\":[]}");
		JsonNode current = aggregate(true, ROW_A);

		assertThat(ScholarshipChangeFields.recorded(Kind.AGGREGATE, old, "timeline")).isFalse();
		assertThat(ScholarshipChangeFields.recorded(Kind.AGGREGATE, old, "periodLocked")).isFalse();
		assertThat(ScholarshipChangeFields.changed(Kind.AGGREGATE, old, current)).isEmpty();
		assertThat(ScholarshipChangeFields.changed(Kind.AGGREGATE, current, old)).isEmpty();
	}
}
