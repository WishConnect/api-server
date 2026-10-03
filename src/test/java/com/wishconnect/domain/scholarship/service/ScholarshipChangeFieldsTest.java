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
}
